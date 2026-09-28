# JSch: unbounded `num-prompts` in keyboard-interactive

A report for [mwiede/jsch](https://github.com/mwiede/jsch), not yet filed.

JSch 2.28.7 (and `master` at `b95bf6b`) allocates the keyboard-interactive prompt arrays from the
server-supplied `num-prompts` without checking it. Sushi works around it with
`app/src/main/java/com/jcraft/jsch/BoundedUserAuthKeyboardInteractive.java` (#199), which also drops
JSch's automatic `Password:` answer — that second change is ours and stays after an upstream fix.

This is a robustness issue, not a vulnerability: it takes a server the client chose and whose host key
it accepted, no data is read or exposed, and the effect stays inside the client's process. It is filed
as a public issue for that reason.

- `issue.md` — the issue body.
- `fix.patch` — the suggested change against upstream `master`, also quoted in the issue.

## Filing it

Search the existing issues first, closed ones included:

```sh
gh issue list --repo mwiede/jsch --state all --search "keyboard-interactive num-prompts"
```

Then, from the repository root:

```sh
gh issue create --repo mwiede/jsch \
  --title "UserAuthKeyboardInteractive allocates prompt arrays from an unvalidated server-supplied count" \
  --body-file docs/process/upstream/jsch-num-prompts/issue.md
```

## After filing

Link the issue from the class comment of `BoundedUserAuthKeyboardInteractive` and from
`docs/improvements/03-connection-reliability.md` §3, and record the number here. Once a JSch release
carries the fix, remove the bound from the copy and bump JSch in `app/build.gradle.kts`.
