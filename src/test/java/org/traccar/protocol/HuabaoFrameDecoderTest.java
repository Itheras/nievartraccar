package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;

public class HuabaoFrameDecoderTest extends ProtocolTest {

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new HuabaoFrameDecoder());

        verifyFrame(
                binary("283734303139303331313138352c312c3030312c454c4f434b2c332c35323934333929"),
                decoder.decode(null, null, binary("283734303139303331313138352c312c3030312c454c4f434b2c332c35323934333929")));

        verifyFrame(
                binary("7e307e087d557e"),
                decoder.decode(null, null, binary("7e307d02087d01557e")));

    }

    @Test
    public void testDecodeUnknownEscape() throws Exception {

        var decoder = inject(new HuabaoFrameDecoder());

        // 0x7d followed by anything other than 0x01 or 0x02 is not an escape pair and has to survive intact
        verifyFrame(
                binary("7e6006000a0138123456780000014f4b7d7265747279007e"),
                decoder.decode(null, null, binary("7e6006000a0138123456780000014f4b7d7265747279007e")));

        // a trailing 0x7d must not consume the closing delimiter
        verifyFrame(
                binary("7e0200000101381234567800004142437d7e"),
                decoder.decode(null, null, binary("7e0200000101381234567800004142437d7e")));

    }

    @Test
    public void testDecodeAlternativeUnknownEscape() throws Exception {

        var decoder = inject(new HuabaoFrameDecoder());

        verifyFrame(
                binary("e7020000060138123456789000010f3e424300e7"),
                decoder.decode(null, null, binary("e7020000060138123456789000010f3e424300e7")));

    }

}
