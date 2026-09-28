/*
 * Copyright (c) 2002-2018 ymnk, JCraft,Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 * and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided with
 * the distribution.
 *
 * 3. The names of the authors may not be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL JCRAFT, INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * Modified by the Sushi SSH client authors: bounded num-prompts, and no automatic answer from
 * session.password (see the class comment).
 */

package com.jcraft.jsch;

/**
 * JSch's {@code UserAuthKeyboardInteractive} from com.github.mwiede:jsch 2.28.7, with two changes.
 *
 * <p><b>The server-supplied {@code num-prompts} is bounded</b> before any array is allocated from
 * it. The original reads {@code int num = buf.getInt()} and allocates {@code new String[num]} and
 * {@code new boolean[num]} straight away, so a server could make the client allocate hundreds of
 * megabytes during authentication and take the whole app down with an OutOfMemoryError. Here a
 * count that is negative, above {@link #MAX_PROMPTS}, or larger than the packet could possibly
 * hold (each prompt needs at least a 4-byte length and a 1-byte echo flag) fails the connection
 * with a {@link JSchException} instead.
 *
 * <p><b>Every round goes to the {@link UIKeyboardInteractive}.</b> The original answers a lone
 * echo-off prompt containing {@code password:} from {@code session.password} itself, without the
 * UserInfo knowing, so the UserInfo cannot keep its own once-per-session rule: a later, differently
 * worded password prompt would get the password again. With that shortcut removed,
 * {@code net.hlan.sushi.KeyboardInteractiveUserInfo} answers every prompt and is the one place that
 * decides when the password is sent.
 *
 * <p>It lives in {@code com.jcraft.jsch} because it needs the package-private members of
 * {@link Session} and {@link Buffer} the original uses. {@code SshClient.configureSession}
 * registers it per session through {@code userauth.keyboard-interactive}, the config key JSch
 * resolves the method's class from. Everything else is unchanged, so keep it in step with the
 * JSch version in {@code app/build.gradle.kts}, and delete it once upstream bounds the count.
 */
class BoundedUserAuthKeyboardInteractive extends UserAuth {
  /** Far above any real server: OpenSSH's PAM sends one prompt per round. */
  static final int MAX_PROMPTS = 64;

  /** A 4-byte string length plus a 1-byte echo flag, for an empty prompt. */
  private static final int MIN_PROMPT_BYTES = 5;

  @Override
  public boolean start(Session session) throws Exception {
    super.start(session);

    if (userinfo != null && !(userinfo instanceof UIKeyboardInteractive)) {
      return false;
    }

    String dest = username + "@" + session.host;
    if (session.port != 22) {
      dest += (":" + session.port);
    }
    boolean cancel = false;

    byte[] _username = null;
    _username = Util.str2byte(username);

    while (true) {

      if (session.auth_failures >= session.max_auth_tries) {
        return false;
      }

      // send
      // byte SSH_MSG_USERAUTH_REQUEST(50)
      // string user name (ISO-10646 UTF-8, as defined in [RFC-2279])
      // string service name (US-ASCII) "ssh-userauth" ? "ssh-connection"
      // string "keyboard-interactive" (US-ASCII)
      // string language tag (as defined in [RFC-3066])
      // string submethods (ISO-10646 UTF-8)
      packet.reset();
      buf.putByte((byte) SSH_MSG_USERAUTH_REQUEST);
      buf.putString(_username);
      buf.putString(Util.str2byte("ssh-connection"));
      // buf.putString("ssh-userauth".getBytes());
      buf.putString(Util.str2byte("keyboard-interactive"));
      buf.putString(Util.empty);
      buf.putString(Util.empty);
      session.write(packet);

      boolean firsttime = true;
      loop: while (true) {
        buf = session.read(buf);
        int command = buf.getCommand() & 0xff;

        if (command == SSH_MSG_USERAUTH_SUCCESS) {
          return true;
        }
        if (command == SSH_MSG_USERAUTH_BANNER) {
          buf.getInt();
          buf.getByte();
          buf.getByte();
          byte[] _message = buf.getString();
          byte[] lang = buf.getString();
          String message = Util.byte2str(_message);
          if (userinfo != null) {
            userinfo.showMessage(message);
          }
          continue loop;
        }
        if (command == SSH_MSG_USERAUTH_FAILURE) {
          buf.getInt();
          buf.getByte();
          buf.getByte();
          byte[] foo = buf.getString();
          int partial_success = buf.getByte();
          // System.err.println(new String(foo)+
          // " partial_success:"+(partial_success!=0));

          if (partial_success != 0) {
            throw new JSchPartialAuthException(Util.byte2str(foo));
          }

          if (firsttime) {
            return false;
            // throw new JSchException("USERAUTH KI is not supported");
            // cancel=true; // ??
          }
          session.auth_failures++;
          break;
        }
        if (command == SSH_MSG_USERAUTH_INFO_REQUEST) {
          firsttime = false;
          buf.getInt();
          buf.getByte();
          buf.getByte();
          String name = Util.byte2str(buf.getString());
          String instruction = Util.byte2str(buf.getString());
          String languate_tag = Util.byte2str(buf.getString());
          int num = buf.getInt();
          if (num < 0 || num > MAX_PROMPTS || (long) num * MIN_PROMPT_BYTES > buf.getLength()) {
            throw new JSchException("keyboard-interactive: server sent an invalid prompt count "
                + num);
          }
          String[] prompt = new String[num];
          boolean[] echo = new boolean[num];
          for (int i = 0; i < num; i++) {
            prompt[i] = Util.byte2str(buf.getString());
            echo[i] = (buf.getByte() != 0);
          }

          byte[][] response = null;

          if (num > 0 || (name.length() > 0 || instruction.length() > 0)) {
            if (userinfo != null) {
              UIKeyboardInteractive kbi = (UIKeyboardInteractive) userinfo;
              String[] _response =
                  kbi.promptKeyboardInteractive(dest, name, instruction, prompt, echo);
              if (_response != null) {
                response = new byte[_response.length][];
                for (int i = 0; i < _response.length; i++) {
                  response[i] = _response[i] != null ? Util.str2byte(_response[i]) : Util.empty;
                }
              }
            }
          }

          // byte SSH_MSG_USERAUTH_INFO_RESPONSE(61)
          // int num-responses
          // string response[1] (ISO-10646 UTF-8)
          // ...
          // string response[num-responses] (ISO-10646 UTF-8)
          packet.reset();
          buf.putByte((byte) SSH_MSG_USERAUTH_INFO_RESPONSE);
          if (num > 0 && (response == null || // cancel
              num != response.length)) {

            if (response == null) {
              // working around the bug in OpenSSH ;-<
              buf.putInt(num);
              for (int i = 0; i < num; i++) {
                buf.putString(Util.empty);
              }
            } else {
              buf.putInt(0);
            }

            if (response == null)
              cancel = true;
          } else {
            buf.putInt(num);
            for (int i = 0; i < num; i++) {
              buf.putString(response[i]);
            }
          }
          session.write(packet);
          /*
           * if(cancel) break;
           */
          continue loop;
        }
        // throw new JSchException("USERAUTH fail ("+command+")");
        return false;
      }
      if (cancel) {
        throw new JSchAuthCancelException("keyboard-interactive");
        // break;
      }
    }
    // return false;
  }
}
