package com.heartwith.uploader;

public interface HeartwithCleartextScope {
    HeartwithCleartextScope NONE = new HeartwithCleartextScope() {
        @Override
        public void enter() {
        }

        @Override
        public void exit() {
        }
    };

    void enter();

    void exit();
}
