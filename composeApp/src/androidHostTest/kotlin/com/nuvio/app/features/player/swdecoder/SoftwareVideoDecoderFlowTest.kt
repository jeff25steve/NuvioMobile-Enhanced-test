package com.nuvio.app.features.player.swdecoder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoftwareVideoDecoderFlowTest {
    @Test
    fun skipsFramesBeforeSeekTargetAndAvoidsConversionWithoutOutput() {
        assertTrue(isSoftwareFrameBeforeOutputStart(900_000L, 1_000_000L))
        assertFalse(isSoftwareFrameBeforeOutputStart(1_000_000L, 1_000_000L))
        assertFalse(isSoftwareFrameBeforeOutputStart(900_000L, androidx.media3.common.C.TIME_UNSET))
        assertFalse(shouldConvertSoftwareFrame(androidx.media3.common.C.VIDEO_OUTPUT_MODE_NONE))
        assertTrue(shouldConvertSoftwareFrame(androidx.media3.common.C.VIDEO_OUTPUT_MODE_SURFACE_YUV))
    }

    @Test
    fun packetIsRetainedUntilSendSucceedsAfterReceiveMakesProgress() {
        val flow = SoftwareVideoDecoderFlowState()
        flow.queuePacket()

        assertEquals(SoftwareDecoderWorkAction.SEND_PACKET, flow.nextAction(false, true))
        flow.onPacketSendResult(SOFTWARE_DECODER_RESULT_AGAIN)
        assertTrue(flow.packetPending)
        assertEquals(SoftwareDecoderWorkAction.RECEIVE_FRAME, flow.nextAction(false, true))

        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_OK)
        assertEquals(SoftwareDecoderWorkAction.RECEIVE_FRAME, flow.nextAction(false, true))
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_AGAIN)

        assertTrue(flow.packetPending)
        assertFalse(flow.receivePending)
        assertEquals(SoftwareDecoderWorkAction.SEND_PACKET, flow.nextAction(false, true))
    }

    @Test
    fun acceptedPacketDrainsEveryAvailableFrameBeforeNextInput() {
        val flow = SoftwareVideoDecoderFlowState()
        flow.queuePacket()
        flow.onPacketSendResult(SOFTWARE_DECODER_RESULT_OK)

        assertEquals(SoftwareDecoderWorkAction.RECEIVE_FRAME, flow.nextAction(true, true))
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_OK)
        assertEquals(SoftwareDecoderWorkAction.RECEIVE_FRAME, flow.nextAction(true, true))
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_OK)
        assertEquals(SoftwareDecoderWorkAction.RECEIVE_FRAME, flow.nextAction(true, true))
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_AGAIN)

        assertEquals(SoftwareDecoderWorkAction.TAKE_INPUT, flow.nextAction(true, true))
    }

    @Test
    fun receiveWaitsForAnAvailableOutputBufferWithoutLosingDecoderState() {
        val flow = SoftwareVideoDecoderFlowState()
        flow.queuePacket()
        flow.onPacketSendResult(SOFTWARE_DECODER_RESULT_OK)

        assertEquals(SoftwareDecoderWorkAction.WAIT, flow.nextAction(false, false))
        assertTrue(flow.receivePending)
        assertEquals(SoftwareDecoderWorkAction.RECEIVE_FRAME, flow.nextAction(false, true))
    }

    @Test
    fun eosRetriesAfterPendingFramesAndQueuesDrainUntilEof() {
        val flow = SoftwareVideoDecoderFlowState()
        flow.queueEndOfStream()
        flow.onEosSendResult(SOFTWARE_DECODER_RESULT_AGAIN)

        assertTrue(flow.eosPending)
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_OK)
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_AGAIN)
        assertEquals(SoftwareDecoderWorkAction.SEND_EOS, flow.nextAction(false, true))

        flow.onEosSendResult(SOFTWARE_DECODER_RESULT_OK)
        assertTrue(flow.draining)
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_OK)
        flow.onFrameReceiveResult(SOFTWARE_DECODER_RESULT_EOF)

        assertTrue(flow.ended)
        assertFalse(flow.receivePending)
        assertEquals(SoftwareDecoderWorkAction.WAIT, flow.nextAction(false, true))
    }

    @Test
    fun flushResetsPacketAndDrainState() {
        val flow = SoftwareVideoDecoderFlowState()
        flow.queuePacket()
        flow.onPacketSendResult(SOFTWARE_DECODER_RESULT_AGAIN)
        flow.reset()

        assertFalse(flow.packetPending)
        assertFalse(flow.receivePending)
        assertFalse(flow.draining)
        assertFalse(flow.ended)
        assertEquals(SoftwareDecoderWorkAction.WAIT, flow.nextAction(false, true))
    }
}
