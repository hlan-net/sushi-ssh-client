#include <jni.h>
#include <pty.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <android/log.h>

#define TAG "sushi-pty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    int master_fd;
    pid_t child_pid;
} PtySession;

JNIEXPORT jlong JNICALL
Java_net_hlan_sushi_LocalShellBackend_nativeStart(
        JNIEnv *env, jclass clazz,
        jstring cmd, jobjectArray argv, jobjectArray envp) {

    const char *cmd_str = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (!cmd_str) return 0L;

    jsize argc = (*env)->GetArrayLength(env, argv);
    jsize envc = (*env)->GetArrayLength(env, envp);

    const char **c_argv = calloc(argc + 1, sizeof(char *));
    const char **c_envp = calloc(envc + 1, sizeof(char *));
    if (!c_argv || !c_envp) {
        free(c_argv);
        free(c_envp);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
        return 0L;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, argv, i);
        c_argv[i] = (*env)->GetStringUTFChars(env, s, NULL);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, envp, i);
        c_envp[i] = (*env)->GetStringUTFChars(env, s, NULL);
    }

    int master_fd;
    pid_t child_pid = forkpty(&master_fd, NULL, NULL, NULL);

    if (child_pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        for (jsize i = 0; i < argc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, argv, i);
            (*env)->ReleaseStringUTFChars(env, s, c_argv[i]);
        }
        for (jsize i = 0; i < envc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, envp, i);
            (*env)->ReleaseStringUTFChars(env, s, c_envp[i]);
        }
        free(c_argv);
        free(c_envp);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);
        return 0L;
    }

    if (child_pid == 0) {
        // child process
        for (jsize i = 0; i < envc; i++) {
            char *kv = strdup(c_envp[i]);
            if (!kv) continue;
            char *eq = strchr(kv, '=');
            if (eq) {
                *eq = '\0';
                setenv(kv, eq + 1, 1);
            }
            free(kv);
        }
        execvp(cmd_str, (char *const *)c_argv);
        _exit(127);
    }

    // parent: set FD_CLOEXEC on master so it doesn't leak into other forks
    fcntl(master_fd, F_SETFD, FD_CLOEXEC);

    for (jsize i = 0; i < argc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, argv, i);
        (*env)->ReleaseStringUTFChars(env, s, c_argv[i]);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (*env)->GetObjectArrayElement(env, envp, i);
        (*env)->ReleaseStringUTFChars(env, s, c_envp[i]);
    }
    free(c_argv);
    free(c_envp);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_str);

    PtySession *sess = malloc(sizeof(PtySession));
    if (!sess) {
        kill(child_pid, SIGHUP);
        close(master_fd);
        return 0L;
    }
    sess->master_fd = master_fd;
    sess->child_pid = child_pid;
    return (jlong)(uintptr_t)sess;
}

JNIEXPORT jint JNICALL
Java_net_hlan_sushi_LocalShellBackend_nativeRead(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray buf) {

    PtySession *sess = (PtySession *)(uintptr_t)handle;
    jsize len = (*env)->GetArrayLength(env, buf);
    jbyte *bytes = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!bytes) return -1;

    ssize_t n;
    do {
        n = read(sess->master_fd, bytes, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n < 0 && errno == EIO) n = -1;

    (*env)->ReleaseByteArrayElements(env, buf, bytes, n > 0 ? 0 : JNI_ABORT);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_net_hlan_sushi_LocalShellBackend_nativeWrite(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray data) {

    PtySession *sess = (PtySession *)(uintptr_t)handle;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -1;

    ssize_t written = 0;
    while (written < len) {
        ssize_t n;
        do {
            n = write(sess->master_fd, bytes + written, (size_t)(len - written));
        } while (n < 0 && errno == EINTR);
        if (n <= 0) break;
        written += n;
    }

    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (jint)written;
}

JNIEXPORT void JNICALL
Java_net_hlan_sushi_LocalShellBackend_nativeResize(
        JNIEnv *env, jclass clazz, jlong handle,
        jint col, jint row, jint widthPx, jint heightPx) {

    PtySession *sess = (PtySession *)(uintptr_t)handle;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)col;
    ws.ws_row = (unsigned short)row;
    ws.ws_xpixel = (unsigned short)widthPx;
    ws.ws_ypixel = (unsigned short)heightPx;
    ioctl(sess->master_fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_net_hlan_sushi_LocalShellBackend_nativeClose(
        JNIEnv *env, jclass clazz, jlong handle) {

    if (handle == 0L) return;
    PtySession *sess = (PtySession *)(uintptr_t)handle;
    kill(sess->child_pid, SIGHUP);
    waitpid(sess->child_pid, NULL, 0);
    close(sess->master_fd);
    free(sess);
}
