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

    /* strdup cmd immediately so the JNI reference can be released at once. */
    const char *cmd_utf = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (!cmd_utf) return 0L;
    char *cmd_str = strdup(cmd_utf);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf);
    if (!cmd_str) return 0L;

    jsize argc = (*env)->GetArrayLength(env, argv);
    jsize envc = (*env)->GetArrayLength(env, envp);

    char **c_argv = calloc(argc + 1, sizeof(char *));
    char **c_envp = calloc(envc + 1, sizeof(char *));
    if (!c_argv || !c_envp) {
        free(c_argv); free(c_envp); free(cmd_str);
        return 0L;
    }

    /*
     * strdup each string immediately after GetStringUTFChars so we can
     * ReleaseStringUTFChars + DeleteLocalRef inside the same iteration.
     * This avoids accumulating JNI local references (limit ~512) and
     * eliminates a second GetObjectArrayElement pass in the parent cleanup.
     */
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, argv, i);
        if (s) {
            const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
            if (utf) {
                c_argv[i] = strdup(utf);
                (*env)->ReleaseStringUTFChars(env, s, utf);
            }
            (*env)->DeleteLocalRef(env, s);
        }
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, envp, i);
        if (s) {
            const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
            if (utf) {
                c_envp[i] = strdup(utf);
                (*env)->ReleaseStringUTFChars(env, s, utf);
            }
            (*env)->DeleteLocalRef(env, s);
        }
    }

    int master_fd;
    pid_t child_pid = forkpty(&master_fd, NULL, NULL, NULL);

    if (child_pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        for (jsize i = 0; i < argc; i++) free(c_argv[i]);
        for (jsize i = 0; i < envc; i++) free(c_envp[i]);
        free(c_argv); free(c_envp); free(cmd_str);
        return 0L;
    }

    if (child_pid == 0) {
        /* child: apply env then exec; _exit on failure so atexit is skipped */
        for (jsize i = 0; i < envc; i++) {
            if (!c_envp[i]) continue;
            char *kv = strdup(c_envp[i]);
            if (!kv) continue;
            char *eq = strchr(kv, '=');
            if (eq) { *eq = '\0'; setenv(kv, eq + 1, 1); }
            /* intentionally not freed — exec replaces the address space */
        }
        execvp(cmd_str, c_argv);
        _exit(127);
    }

    /* parent: FD_CLOEXEC so master_fd doesn't leak into later forks */
    fcntl(master_fd, F_SETFD, FD_CLOEXEC);
    for (jsize i = 0; i < argc; i++) free(c_argv[i]);
    for (jsize i = 0; i < envc; i++) free(c_envp[i]);
    free(c_argv); free(c_envp); free(cmd_str);

    PtySession *sess = malloc(sizeof(PtySession));
    if (!sess) {
        kill(child_pid, SIGHUP);
        waitpid(child_pid, NULL, 0); /* reap to avoid zombie */
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
