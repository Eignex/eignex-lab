package com.eignex.lab.xcsp3cpsat;

/** Loads OR-Tools' JNI library: from `java.library.path` in the image, else unpacked from the classpath jar. */
final class Native {
    private static boolean loaded;

    private Native() {
    }

    static synchronized void load() {
        if (loaded) return;
        try {
            System.loadLibrary("jniortools");
        } catch (UnsatisfiedLinkError e) {
            com.google.ortools.Loader.loadNativeLibraries();
        }
        loaded = true;
    }
}
