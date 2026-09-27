import java.nio.ByteBuffer;

public class WireCheck {
    public static void main(String[] a) {
        byte[] m = new byte[50];
        String hex = "000181800001000100000000036170690874656c656772616d036f72670000010001c00c00010001000000760004959aa66e";
        for (int i = 0; i < m.length; i++) m[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        ByteBuffer b = ByteBuffer.wrap(m);
        System.out.println("id=" + (b.getShort() & 0xffff));
        int flags = b.getShort() & 0xffff;
        System.out.println("flags=0x" + Integer.toHexString(flags));
        int qd = b.getShort() & 0xffff, an = b.getShort() & 0xffff;
        int ns = b.getShort() & 0xffff, ar = b.getShort() & 0xffff;
        System.out.println("qd=" + qd + " an=" + an + " ns=" + ns + " ar=" + ar);
        System.out.println("position after header=" + b.position());
        b.position(12);
        for (int i = 0; i < qd; i++) skip(b);
        System.out.println("position after question=" + b.position());
        for (int i = 0; i < an; i++) {
            skip(b);
            int t = b.getShort() & 0xffff, c = b.getShort() & 0xffff;
            long ttl = b.getInt() & 0xffffffffL;
            int len = b.getShort() & 0xffff;
            int start = b.position();
            System.out.println("answer " + i + " type=" + t + " class=" + c + " ttl=" + ttl + " len=" + len);
            if (t == 1 && c == 1 && len == 4) {
                String ip = (b.get() & 0xff) + "." + (b.get() & 0xff) + "." + (b.get() & 0xff) + "." + (b.get() & 0xff);
                System.out.println("  IP=" + ip);
            }
            b.position(start + len);
        }
    }
    static void skip(ByteBuffer b) {
        while (true) {
            int len = b.get() & 0xff;
            if (len == 0) return;
            if ((len & 0xc0) == 0xc0) { b.get(); return; }
            b.position(b.position() + len);
        }
    }
}
