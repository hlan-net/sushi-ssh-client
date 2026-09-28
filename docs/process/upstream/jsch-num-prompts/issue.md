## Summary

`UserAuthKeyboardInteractive.start()` reads `num-prompts` from `SSH_MSG_USERAUTH_INFO_REQUEST` and allocates `String[num]` and `boolean[num]` before reading any prompt, without checking the value:

```java
int num = buf.getInt();
String[] prompt = new String[num];
boolean[] echo = new boolean[num];
```

([`UserAuthKeyboardInteractive.java#L123-L125`](https://github.com/mwiede/jsch/blob/b95bf6b/src/main/java/com/jcraft/jsch/UserAuthKeyboardInteractive.java#L123-L125), unchanged in 2.28.7 and on `master` at b95bf6b.)

A malformed or misbehaving server can therefore make the client allocate arrays of up to 2³¹−1 elements during authentication. The rest of the packet parsing is already bounded: `Buffer.getString()` clamps lengths to 256 KiB, and `Buffer.getBytes()` checks `getLength()`. Only this count is not checked.

## Impact

This is a robustness issue rather than a security vulnerability. The request comes from the server after key exchange, so it takes a server the client chose to connect to and whose host key it accepted. That server could deny service in simpler ways, and the effect stays inside the client's own process. The problem is how the failure surfaces: an `Error` or an unchecked exception from `connect()`, where a `JSchException` would be expected. Depending on the value and the heap:

| `num-prompts` | Result |
|---|---|
| `0x7fffffff` | `OutOfMemoryError: Requested array size exceeds VM limit`, immediately |
| e.g. `100000000` | about 500 MB allocated (`String[]` plus `boolean[]`) before any prompt is read. On a smaller heap this is an `OutOfMemoryError`; on a larger one the parse then fails with `ArrayIndexOutOfBoundsException`, because the packet holds no prompts |
| negative | `NegativeArraySizeException`, which `Session.connect()` rethrows as an unchecked exception rather than a `JSchException` |

The practical cost is that one misbehaving host can take down more than its own connection. On Android, with a per-app heap of a few hundred MB, the `OutOfMemoryError` ends the whole app; we found this in an Android SSH client. In a JVM that uses JSch for many hosts at once, such as a deployment or monitoring tool, it can take other hosts' sessions down with it.

Which clients take this code path: `keyboard-interactive` is in the default `PreferredAuthentications` (`gssapi-with-mic,publickey,keyboard-interactive,password`), and `start()` only returns early when a `UserInfo` is set that is *not* a `UIKeyboardInteractive`. So this affects both clients whose `UserInfo` implements `UIKeyboardInteractive`, and clients that set no `UserInfo` at all, which is a common way to use the library.

## Reproduction

A server that answers the `keyboard-interactive` request with one `INFO_REQUEST` claiming 100,000,000 prompts and carrying none. With Apache MINA sshd 2.19.0 as the server:

```java
UserAuthFactory hostile = new UserAuthFactory() {
  @Override public String getName() { return "keyboard-interactive"; }
  @Override public UserAuth createUserAuth(ServerSession session) {
    return new AbstractUserAuth(getName()) {
      @Override protected Boolean doAuth(Buffer buffer, boolean init) throws Exception {
        if (!init) return false;
        Buffer req = getServerSession().createBuffer(SshConstants.SSH_MSG_USERAUTH_INFO_REQUEST);
        req.putString("");          // name
        req.putString("");          // instruction
        req.putString("");          // language tag
        req.putInt(100_000_000L);   // num-prompts, with no prompts following
        getServerSession().writePacket(req);
        return null;
      }
    };
  }
};
sshd.setUserAuthFactories(Collections.singletonList(hostile));
```

Then connect any JSch session to it with host-key checking satisfied, for example `StrictHostKeyChecking=no` and no `UserInfo`. The client allocates the two arrays and fails as described above.

## Suggested fix

Validate the count before allocating. Every prompt needs at least a 4-byte string length and a 1-byte echo flag, so the remaining packet length gives a natural upper bound without picking an arbitrary limit:

```diff
--- a/src/main/java/com/jcraft/jsch/UserAuthKeyboardInteractive.java
+++ b/src/main/java/com/jcraft/jsch/UserAuthKeyboardInteractive.java
@@ -121,6 +121,12 @@ class UserAuthKeyboardInteractive extends UserAuth {
           String instruction = Util.byte2str(buf.getString());
           String languate_tag = Util.byte2str(buf.getString());
           int num = buf.getInt();
+          // Each prompt needs at least a 4-byte string length and a 1-byte echo flag, so a count
+          // the remaining packet cannot hold is invalid; checking it here keeps a misbehaving
+          // server from making the client allocate arrays of up to 2^31-1 elements.
+          if (num < 0 || (long) num * 5 > buf.getLength()) {
+            throw new JSchException("USERAUTH INFO_REQUEST: invalid num-prompts " + num);
+          }
           String[] prompt = new String[num];
           boolean[] echo = new boolean[num];
           for (int i = 0; i < num; i++) {
```

With `PACKET_MAX_SIZE` at 256 KiB, this caps `num` at about 52,000, which bounds both arrays to a few hundred KB. A `JSchException` is also the type `Session.connect()` already reports, rather than an `Error` or an unchecked exception. We are happy to open a PR with this change and a test if that helps.

We currently ship this check in a patched copy of the class, registered through `userauth.keyboard-interactive`, and would like to drop that copy once an upstream release carries the fix.
