package local.screenink;

import android.accessibilityservice.TouchInteractionController;
import android.util.Log;
import android.view.MotionEvent;

/** Routes touches outside the floating window to the frozen drawing viewport. */
final class FingerInput implements TouchInteractionController.Callback {
    interface Host {
        boolean routeToApp(float x, float y);
        void moveViewport(MotionEvent event);
        void finishViewport();
    }

    private final TouchInteractionController controller;
    private final Host host;
    private boolean registered;
    private boolean viewportGesture;

    FingerInput(TouchInteractionController controller, Host host) {
        this.controller = controller;
        this.host = host;
    }

    void setEnabled(boolean enabled) {
        if (enabled == registered) return;
        registered = enabled;
        if (enabled) controller.registerCallback(null, this);
        else {
            reset();
            controller.unregisterCallback(this);
        }
    }

    @Override public void onStateChanged(int state) {
        if (state == TouchInteractionController.STATE_CLEAR) reset();
    }

    @Override public void onMotionEvent(MotionEvent event) {
        if (!registered || event.getPointerCount() == 0
            || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            if (host.routeToApp(event.getX(), event.getY())) {
                delegate();
                return;
            }
            viewportGesture = true;
        }
        if (viewportGesture) host.moveViewport(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) reset();
    }

    private void delegate() {
        if (!registered || viewportGesture
            || controller.getState() != TouchInteractionController.STATE_TOUCH_INTERACTING) return;
        try {
            controller.requestDelegating();
        } catch (IllegalStateException ended) {
            Log.d("ScreenInk", "Finger interaction already ended");
        }
    }

    private void reset() {
        if (viewportGesture) host.finishViewport();
        viewportGesture = false;
    }
}
