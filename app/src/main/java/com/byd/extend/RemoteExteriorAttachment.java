package com.byd.extend;

import android.os.Parcel;

/** Acknowledged exterior attachment, independent of successful power preparation. */
final class RemoteExteriorAttachment {
    final boolean attached;
    final String errorReference;

    RemoteExteriorAttachment(boolean attached, String errorReference) {
        if (errorReference != null && (attached || !errorReference.matches("[a-f0-9]{32}")))
            throw new IllegalArgumentException("invalid exterior attachment error reference");
        this.attached = attached;
        this.errorReference = errorReference;
    }

    boolean needsRecovery(int view) {
        return RemoteCameraView.needsExterior(view) && !attached;
    }

    void writeToParcel(Parcel reply) {
        reply.writeInt(attached ? 1 : 0);
        reply.writeString(errorReference);
    }

    static RemoteExteriorAttachment readFromParcel(Parcel reply) {
        if (reply.dataAvail() < 8)
            throw new IllegalArgumentException("missing exterior attachment acknowledgement");
        int flag = reply.readInt();
        if (flag != 0 && flag != 1)
            throw new IllegalArgumentException("invalid exterior attachment flag: " + flag);
        return new RemoteExteriorAttachment(flag == 1, reply.readString());
    }
}
