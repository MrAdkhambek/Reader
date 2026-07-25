package com.adkhambek.reader.nfc.card.bean;

import java.util.Collections;
import java.util.List;

public record Card(String uid, List<CardApp> apps) {

    public Card(String uid, List<CardApp> apps) {
        this.uid = uid;
        this.apps = (apps != null) ? Collections.unmodifiableList(apps) : Collections.emptyList();
    }

    public boolean isUnknown() {
        return apps.isEmpty();
    }
}
