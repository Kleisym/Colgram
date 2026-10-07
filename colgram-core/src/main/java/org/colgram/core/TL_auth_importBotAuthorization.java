package org.colgram.core;

import java.lang.reflect.Method;

/**
 * TL_auth_importBotAuthorization — MTProto request for logging into Telegram using a Bot Token.
 * 
 * auth.importBotAuthorization#67a3ffca flags:# api_id:int api_hash:string bot_auth_token:string = auth.Authorization;
 * 
 * Uses pure reflection on org.telegram.tgnet classes so it compiles independently of Telegram's build classpath.
 */
public class TL_auth_importBotAuthorization {
    public static final int constructor = 0x67a3ff2c;

    public int flags = 0;
    public int api_id;
    public String api_hash;
    public String bot_auth_token;

    public Object createTLObject() {
        try {
            Class<?> tlObjectClass = Class.forName("org.telegram.tgnet.TLObject");
            Class<?> inStreamClass = Class.forName("org.telegram.tgnet.InputSerializedData");
            Class<?> outStreamClass = Class.forName("org.telegram.tgnet.OutputSerializedData");
            Class<?> tlrpcClass = Class.forName("org.telegram.tgnet.TLRPC");
            Class<?> authClass = Class.forName("org.telegram.tgnet.TLRPC$auth_Authorization");

            return java.lang.reflect.Proxy.newProxyInstance(
                    tlObjectClass.getClassLoader(),
                    new Class[]{tlObjectClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("serializeToStream".equals(name) && args.length == 1) {
                            Object stream = args[0];
                            Method writeInt32 = stream.getClass().getMethod("writeInt32", int.class);
                            Method writeString = stream.getClass().getMethod("writeString", String.class);

                            writeInt32.invoke(stream, constructor);
                            writeInt32.invoke(stream, flags);
                            writeInt32.invoke(stream, api_id);
                            writeString.invoke(stream, api_hash != null ? api_hash : "");
                            writeString.invoke(stream, bot_auth_token != null ? bot_auth_token : "");
                            return null;
                        } else if ("deserializeResponse".equals(name) && args.length >= 3) {
                            Object stream = args[0];
                            int c = (int) args[1];
                            boolean exception = (boolean) args[2];
                            Method deserialize = authClass.getMethod("TLdeserialize", inStreamClass, int.class, boolean.class);
                            return deserialize.invoke(null, stream, c, exception);
                        }
                        return null;
                    }
            );
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }
}
