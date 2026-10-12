SHELL := /bin/bash

-include .env
PHONE_HOST ?= pixel-5
SSH_PORT   ?= 8022
APK        := app/build/outputs/apk/debug/app-debug.apk

# macOS defaults: Homebrew JDK 17 + default SDK location. On other systems
# (Linux CI, containers) respect a pre-set JAVA_HOME / ANDROID_HOME instead.
UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
export JAVA_HOME := /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME := $(HOME)/Library/Android/sdk
export PATH := $(JAVA_HOME)/bin:$(PATH)
endif
ANDROID_HOME ?= $(HOME)/android-sdk
export ANDROID_HOME
export ANDROID_SDK_ROOT := $(ANDROID_HOME)

.PHONY: build test install adb-install clean

build:
	./gradlew assembleDebug
	@echo "APK: $(APK)"

test:
	./gradlew testDebugUnitTest

install: build
	scp -P $(SSH_PORT) $(APK) $(PHONE_HOST):~/storage/downloads/phone-whisper.apk
	ssh -p $(SSH_PORT) $(PHONE_HOST) "termux-open ~/storage/downloads/phone-whisper.apk"
	@echo "APK sent — approve install on phone"

adb-install: build
	$(ANDROID_HOME)/platform-tools/adb install -r $(APK)

clean:
	./gradlew clean
