import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 生成 Windows .ico 应用图标（内嵌 PNG，支持 256x256） */
public class MakeIcon {
    public static void main(String[] args) throws Exception {
        String out = args[0];
        BufferedImage img = (BufferedImage) Main.appIcon(256);

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        byte[] pngBytes = png.toByteArray();

        ByteBuffer header = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0);      // reserved
        header.putShort((short) 1);      // type = icon
        header.putShort((short) 1);      // image count
        header.put((byte) 0);            // width 256 -> 0
        header.put((byte) 0);            // height 256 -> 0
        header.put((byte) 0);            // palette
        header.put((byte) 0);            // reserved
        header.putShort((short) 1);      // color planes
        header.putShort((short) 32);     // bits per pixel
        header.putInt(pngBytes.length);  // size of image data
        header.putInt(22);               // offset

        try (OutputStream os = new FileOutputStream(out)) {
            os.write(header.array());
            os.write(pngBytes);
        }
        System.out.println("已生成图标: " + out + " (" + (22 + pngBytes.length) + " 字节)");
    }
}
