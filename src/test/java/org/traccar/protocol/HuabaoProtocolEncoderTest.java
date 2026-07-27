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
    public void testEncodeSetConnectionGosafeDomain() throws Exception {

        var encoder = inject(new HuabaoProtocolEncoder(null));
        encoder.setModelOverride("gosafe");

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_SET_CONNECTION);
        command.set(Command.KEY_SERVER, "bvtelematics.com");
        command.set(Command.KEY_PORT, 5020);
        command.set(Command.KEY_DEVICE_PASSWORD, "GSGPS");

        verifyCommand(encoder, command, binary(
                "7e8304002945678901234500004e00263c535047532a503a"
                        + "47534750532a513a627674656c656d61746963732e636f6d2c353032303e277e"));

    }

    @Test
    public void testEncodeSetConnectionGosafeAddress() throws Exception {

        var encoder = inject(new HuabaoProtocolEncoder(null));
        encoder.setModelOverride("gosafe");

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_SET_CONNECTION);
        command.set(Command.KEY_SERVER, "34.198.76.158");
        command.set(Command.KEY_PORT, 9808);
        command.set(Command.KEY_DEVICE_PASSWORD, "GSGPS");

        verifyCommand(encoder, command, binary(
                "7e8304002845678901234500004e00253c535047532a503a"
                        + "47534750532a543a3033342e3139382e3037362e3135382c393830383e487e"));

    }

    @Test
    public void testEncodeCustomText() throws Exception {

        var encoder = inject(new HuabaoProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CUSTOM);
        command.set(Command.KEY_DATA, "<SPGS*RLS>");

        verifyCommand(encoder, command, binary(
                "7e8304000d45678901234500004e000a3c535047532a524c533e707e"));

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
