/*
 * Copyright (c) 2010-2021 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.activities.main.conference.viewmodels

import android.Manifest
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.activities.voip.ConferenceDisplayMode
import org.linphone.core.*
import org.linphone.core.tools.Log
import org.linphone.utils.AudioRouteUtils
import org.linphone.utils.Event
import org.linphone.utils.PermissionHelper

class ConferenceWaitingRoomViewModel : ViewModel() {
    val subject = MutableLiveData<String>()

    val isMicrophoneMuted = MutableLiveData<Boolean>()

    val audioRoutesEnabled = MutableLiveData<Boolean>()

    val audioRoutesSelected = MutableLiveData<Boolean>()

    val isSpeakerSelected = MutableLiveData<Boolean>()

    val isBluetoothHeadsetSelected = MutableLiveData<Boolean>()

    val layoutMenuSelected = MutableLiveData<Boolean>()

    val selectedLayout = MutableLiveData<ConferenceDisplayMode>()

    val isVideoAvailable = MutableLiveData<Boolean>()

    val isVideoEnabled = MutableLiveData<Boolean>()

    val isSwitchCameraAvailable = MutableLiveData<Boolean>()

    val joinInProgress = MutableLiveData<Boolean>()

    val askPermissionEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val cancelConferenceJoiningEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val joinConferenceEvent: MutableLiveData<Event<CallParams>> by lazy {
        MutableLiveData<Event<CallParams>>()
    }

    val leaveWaitingRoomEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    private val callParams: CallParams = coreContext.core.createCallParams(null)!!

    private val listener: CoreListenerStub = object : CoreListenerStub() {
        override fun onAudioDevicesListUpdated(core: Core) {
            Log.i("[Conference Waiting Room] Audio devices list updated")
            onAudioDevicesListUpdated()
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            if (state == Call.State.Connected) {
                Log.i("[Conference Waiting Room] Call is now connected, leaving waiting room fragment")
                leaveWaitingRoomEvent.value = Event(true)
            }
        }
    }

    init {
        val core = coreContext.core
        core.addListener(listener)

        callParams.isMicEnabled = PermissionHelper.get().hasRecordAudioPermission() && coreContext.core.isMicEnabled
        Log.i("[Conference Waiting Room] Microphone will be ${if (callParams.isMicEnabled) "enabled" else "muted"}")
        updateMicState()

        callParams.isVideoEnabled = isVideoAvailableInCore()
        callParams.videoDirection = if (core.videoActivationPolicy.automaticallyInitiate) MediaDirection.SendRecv else MediaDirection.RecvOnly
        Log.i("[Conference Waiting Room] Video will be ${if (callParams.isVideoEnabled) "enabled" else "disabled"}")
        updateVideoState()

        layoutMenuSelected.value = false
        updateLayout()

        if (AudioRouteUtils.isBluetoothAudioRouteAvailable()) {
            setBluetoothAudioRoute()
        } else if (isVideoAvailableInCore() && isVideoEnabled.value == true) {
            setSpeakerAudioRoute()
        } else {
            setEarpieceAudioRoute()
        }
        onAudioDevicesListUpdated()
    }

    override fun onCleared() {
        coreContext.core.removeListener(listener)

        super.onCleared()
    }

    fun cancel() {
        cancelConferenceJoiningEvent.value = Event(true)
    }

    fun start() {
        // Hide menus
        audioRoutesSelected.value = false
        layoutMenuSelected.value = false

        joinInProgress.value = true
        joinConferenceEvent.value = Event(callParams)
    }

    fun toggleMuteMicrophone() {
        if (!PermissionHelper.get().hasRecordAudioPermission()) {
            askPermissionEvent.value = Event(Manifest.permission.RECORD_AUDIO)
            return
        }

        callParams.isMicEnabled = !callParams.isMicEnabled
        Log.i("[Conference Waiting Room] Microphone will be ${if (callParams.isMicEnabled) "enabled" else "muted"}")
        updateMicState()
    }

    fun enableMic() {
        Log.i("[Conference Waiting Room] Microphone will be enabled")
        callParams.isMicEnabled = true
        updateMicState()
    }

    fun toggleSpeaker() {
        if (isSpeakerSelected.value == true) {
            setEarpieceAudioRoute()
        } else {
            setSpeakerAudioRoute()
        }
    }

    fun toggleAudioRoutesMenu() {
        audioRoutesSelected.value = audioRoutesSelected.value != true
    }

    fun setBluetoothAudioRoute() {
        Log.i("[Conference Waiting Room] Set default output audio device to Bluetooth")
        callParams.outputAudioDevice = coreContext.core.audioDevices.find {
            it.type == AudioDevice.Type.Bluetooth && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        }
        callParams.inputAudioDevice = coreContext.core.audioDevices.find {
            it.type == AudioDevice.Type.Bluetooth && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
        }
        updateAudioRouteState()
    }

    fun setSpeakerAudioRoute() {
        Log.i("[Conference Waiting Room] Set default output audio device to Speaker")
        callParams.outputAudioDevice = coreContext.core.audioDevices.find {
            it.type == AudioDevice.Type.Speaker && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        }
        callParams.inputAudioDevice = coreContext.core.audioDevices.find {
            it.type == AudioDevice.Type.Microphone && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
        }
        updateAudioRouteState()
    }

    fun setEarpieceAudioRoute() {
        Log.i("[Conference Waiting Room] Set default output audio device to Earpiece")
        callParams.outputAudioDevice = coreContext.core.audioDevices.find {
            it.type == AudioDevice.Type.Earpiece && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        }
        callParams.inputAudioDevice = coreContext.core.audioDevices.find {
            it.type == AudioDevice.Type.Microphone && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
        }
        updateAudioRouteState()
    }

    fun toggleLayoutMenu() {
        layoutMenuSelected.value = layoutMenuSelected.value != true
    }

    fun setMosaicLayout() {
        Log.i("[Conference Waiting Room] Set default layout to Mosaic")

        callParams.conferenceVideoLayout = ConferenceLayout.Grid
        callParams.isVideoEnabled = isVideoAvailableInCore()

        updateLayout()
        updateVideoState()
        layoutMenuSelected.value = false
    }

    fun setActiveSpeakerLayout() {
        Log.i("[Conference Waiting Room] Set default layout to ActiveSpeaker")

        callParams.conferenceVideoLayout = ConferenceLayout.ActiveSpeaker
        callParams.isVideoEnabled = isVideoAvailableInCore()

        updateLayout()
        updateVideoState()
        layoutMenuSelected.value = false
    }

    fun setAudioOnlyLayout() {
        Log.i("[Conference Waiting Room] Set default layout to AudioOnly, disabling video in call")
        callParams.isVideoEnabled = false
        updateLayout()
        updateVideoState()
        layoutMenuSelected.value = false
    }

    fun toggleVideo() {
        if (!PermissionHelper.get().hasCameraPermission()) {
            askPermissionEvent.value = Event(Manifest.permission.CAMERA)
            return
        }
        callParams.isVideoEnabled = isVideoAvailableInCore()
        callParams.videoDirection = if (callParams.videoDirection == MediaDirection.SendRecv) MediaDirection.RecvOnly else MediaDirection.SendRecv
        Log.i("[Conference Waiting Room] Video will be ${if (callParams.isVideoEnabled) "enabled" else "disabled"}")
        updateVideoState()
    }

    fun enableVideo() {
        Log.i("[Conference Waiting Room] Video will be enabled")
        callParams.isVideoEnabled = isVideoAvailableInCore()
        callParams.videoDirection = MediaDirection.SendRecv
        updateVideoState()
    }

    fun switchCamera() {
        Log.i("[Conference Waiting Room] Switching camera")
        coreContext.switchCamera()
    }

    private fun updateMicState() {
        isMicrophoneMuted.value = !callParams.isMicEnabled
    }

    private fun onAudioDevicesListUpdated() {
        val bluetoothDeviceAvailable = AudioRouteUtils.isBluetoothAudioRouteAvailable()
        audioRoutesEnabled.value = bluetoothDeviceAvailable

        if (!bluetoothDeviceAvailable) {
            audioRoutesSelected.value = false
            Log.w("[Conference Waiting Room] Bluetooth device no longer available, switching back to default microphone & earpiece/speaker")
            if (isBluetoothHeadsetSelected.value == true) {
                for (audioDevice in coreContext.core.audioDevices) {
                    if (isVideoEnabled.value == true) {
                        if (audioDevice.type == AudioDevice.Type.Speaker) {
                            callParams.outputAudioDevice = audioDevice
                        }
                    } else {
                        if (audioDevice.type == AudioDevice.Type.Earpiece) {
                            callParams.outputAudioDevice = audioDevice
                        }
                    }
                    if (audioDevice.type == AudioDevice.Type.Microphone) {
                        callParams.inputAudioDevice = audioDevice
                    }
                }
            }
        }

        updateAudioRouteState()
    }

    private fun updateAudioRouteState() {
        val outputDeviceType = callParams.outputAudioDevice?.type
        isSpeakerSelected.value = outputDeviceType == AudioDevice.Type.Speaker
        isBluetoothHeadsetSelected.value = outputDeviceType == AudioDevice.Type.Bluetooth
    }

    private fun updateLayout() {
        if (!callParams.isVideoEnabled) {
            selectedLayout.value = ConferenceDisplayMode.AUDIO_ONLY
        } else {
            selectedLayout.value = when (callParams.conferenceVideoLayout) {
                ConferenceLayout.Grid -> ConferenceDisplayMode.GRID
                else -> ConferenceDisplayMode.ACTIVE_SPEAKER
            }
        }
    }

    private fun updateVideoState() {
        isVideoAvailable.value = callParams.isVideoEnabled
        isVideoEnabled.value = callParams.isVideoEnabled && callParams.videoDirection == MediaDirection.SendRecv
        isSwitchCameraAvailable.value = callParams.isVideoEnabled && coreContext.showSwitchCameraButton()
        coreContext.core.isVideoPreviewEnabled = callParams.isVideoEnabled
    }

    private fun isVideoAvailableInCore(): Boolean {
        val core = coreContext.core
        return core.isVideoCaptureEnabled || core.isVideoPreviewEnabled
    }
}
