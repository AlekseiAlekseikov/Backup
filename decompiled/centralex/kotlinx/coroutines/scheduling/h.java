package kotlinx.coroutines.scheduling;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import q0.n;
import w0.od;
/* loaded from: classes.dex */
public final class h {

    /* renamed from: a  reason: collision with root package name */
    public final int f2371a;

    /* JADX WARN: Type inference failed for: r8v5, types: [java.lang.Object, q0.n] */
    public static n a(DataInputStream dataInputStream) {
        byte[] bArr = new byte[24];
        dataInputStream.readFully(bArr);
        ByteBuffer order = ByteBuffer.wrap(bArr).order(ByteOrder.LITTLE_ENDIAN);
        int i3 = order.getInt();
        int i4 = order.getInt();
        int i5 = order.getInt();
        int i6 = order.getInt();
        int i7 = order.getInt();
        if ((~i3) == order.getInt()) {
            byte[] bArr2 = new byte[i6];
            if (i6 > 0) {
                dataInputStream.readFully(bArr2);
            }
            int i8 = 0;
            for (int i9 = 0; i9 < i6; i9++) {
                i8 += bArr2[i9] & 255;
            }
            if (i8 == i7) {
                ?? obj = new Object();
                obj.f2965a = i3;
                obj.b = i4;
                obj.f2966c = i5;
                obj.f2967d = bArr2;
                return obj;
            }
            throw new IOException("checksum ADB invalido");
        }
        throw new IOException("cabecalho ADB invalido");
    }

    public static void d(OutputStream outputStream, int i3, int i4, int i5, byte[] bArr) {
        ByteBuffer order = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        order.putInt(i3);
        order.putInt(i4);
        order.putInt(i5);
        order.putInt(bArr.length);
        int i6 = 0;
        for (byte b : bArr) {
            i6 += b & 255;
        }
        order.putInt(i6);
        order.putInt(~i3);
        outputStream.write(order.array());
        outputStream.write(bArr);
        outputStream.flush();
    }

    public String b(int i3, String str) {
        long elapsedRealtime = SystemClock.elapsedRealtime();
        try {
            return c(i3, str);
        } finally {
            od.f4043a.c(od.f4046e, SystemClock.elapsedRealtime() - elapsedRealtime, false);
        }
    }

    public String c(int i3, String str) {
        long j3;
        int i4;
        int i5;
        Charset charset = StandardCharsets.UTF_8;
        byte[] bytes = ("shell:" + str).getBytes(charset);
        int length = bytes.length;
        int i6 = length + 1;
        byte[] bArr = new byte[i6];
        System.arraycopy(bytes, 0, bArr, 0, bytes.length);
        bArr[length] = 0;
        if (i6 > 4096) {
            throw new IOException("comando shell excede payload ADB: " + i6 + " bytes > 4096; use AdbBigScript");
        }
        long currentTimeMillis = System.currentTimeMillis() + i3;
        Socket socket = new Socket();
        try {
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", this.f2371a), i3);
                socket.setSoTimeout(i3);
                DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());
                OutputStream outputStream = socket.getOutputStream();
                byte[] bytes2 = "host::central-geely".getBytes(charset);
                int length2 = bytes2.length;
                byte[] bArr2 = new byte[length2 + 1];
                System.arraycopy(bytes2, 0, bArr2, 0, bytes2.length);
                bArr2[length2] = 0;
                d(outputStream, 1314410051, 16777216, 4096, bArr2);
                do {
                    long currentTimeMillis2 = currentTimeMillis - System.currentTimeMillis();
                    if (currentTimeMillis2 <= 0) {
                        throw new IOException("prazo total do comando ADB estourado");
                    }
                    j3 = 2147483647L;
                    socket.setSoTimeout((int) Math.min(currentTimeMillis2, 2147483647L));
                    i4 = a(dataInputStream).f2965a;
                    i5 = 1213486401;
                    if (i4 == 1213486401) {
                        throw new IOException("adbd pediu autenticacao por chave");
                    }
                } while (i4 != 1314410051);
                if (i4 != 1314410051) {
                    throw new IOException("handshake ADB inesperado");
                }
                d(outputStream, 1313165391, 1, 0, bArr);
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                boolean z2 = false;
                int i7 = 0;
                while (true) {
                    OutputStream outputStream2 = outputStream;
                    long currentTimeMillis3 = currentTimeMillis - System.currentTimeMillis();
                    if (currentTimeMillis3 <= 0) {
                        throw new IOException("prazo total do comando ADB estourado");
                    }
                    socket.setSoTimeout((int) Math.min(currentTimeMillis3, j3));
                    n a3 = a(dataInputStream);
                    int i8 = a3.f2965a;
                    int i9 = a3.b;
                    if (i8 == i5) {
                        throw new IOException("adbd pediu autenticacao por chave");
                    }
                    if (i8 == 1497451343) {
                        if (a3.f2966c == 1) {
                            z2 = true;
                            i7 = i9;
                        }
                        outputStream = outputStream2;
                    } else if (i8 != 1163154007) {
                        if (i8 == 1163086915) {
                            d(outputStream2, 1163086915, 1, i7, new byte[0]);
                            String trim = byteArrayOutputStream.toString("UTF-8").trim();
                            socket.close();
                            return trim;
                        }
                        throw new IOException("mensagem ADB inesperada: 0x" + Integer.toHexString(i8));
                    } else {
                        if (!z2) {
                            z2 = true;
                            i7 = i9;
                        }
                        byteArrayOutputStream.write((byte[]) a3.f2967d);
                        d(outputStream2, 1497451343, 1, i9, new byte[0]);
                        outputStream = outputStream2;
                        i5 = 1213486401;
                    }
                    j3 = 2147483647L;
                }
            } catch (Throwable th) {
                th = th;
                Throwable th2 = th;
                try {
                    socket.close();
                } catch (Throwable th3) {
                    th2.addSuppressed(th3);
                }
                throw th2;
            }
        } catch (Throwable th4) {
            th = th4;
        }
    }
}
