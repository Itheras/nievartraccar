package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

public class HuabaoProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncodeEngineStop() throws Exception {

        var encoder = inject(new HuabaoProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        verifyCommand(encoder, command, binary("7e810500014567890123450000f0b97e"));

    }

    @Test
    public void testEncodeSetConnection() throws Exception {

        var encoder = inject(new HuabaoProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_SET_CONNECTION);
        command.set(Command.KEY_SERVER, "bvtelematics.com");
        command.set(Command.KEY_PORT, 5020);

        verifyCommand(encoder, command, binary(
                "7e8103001f456789012345000002000000131062767465"
                        + "6c656d61746963732e636f6d00000018040000139c817e"));

    }

    @Test
    public void testEncodeSetConnectionCombinedAddress() throws Exception {

        var encoder = inject(new HuabaoProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_SET_CONNECTION);
        command.set(Command.KEY_SERVER, "bvtelematics.com:5020");
        command.set(Command.KEY_PORT, 0);

        verifyCommand(encoder, command, binary(
                "7e8103001f456789012345000002000000131062767465"
                        + "6c656d61746963732e636f6d00000018040000139c817e"));

    }

}
