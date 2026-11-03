package app.ouyabrowser;

/** A modal, controller-driven screen layered above the page. */
interface Overlay {
    boolean isOpen();

    /** Handles a controller button. repeat is true for auto-repeat while a direction is held. Returns true if consumed. */
    boolean onButton(Btn b, boolean repeat);
}
