package org.python.interop.fixtures;

/**
 * An interface with more than one method, which is what a namespace has to satisfy to be exported.
 *
 * <p>{@code Runnable} and {@code Function} only prove the single-method case, and that case is the one
 * where forwarding everything to one callable is the right answer. This is the case where it is not.
 */
public interface Panel {

    void onOpen(String title);

    int onClose(int code, String reason);
}
