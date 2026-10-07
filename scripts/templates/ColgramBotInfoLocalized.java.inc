    /** bots.botInfo#e8a775b0 returned by bots.getBotInfo for the current bot. */
    public static class TL_botInfoLocalized extends BotInfo {
        public static final int constructor = 0xe8a775b0;
        public String name;
        public String about;

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            name = stream.readString(exception);
            about = stream.readString(exception);
            description = stream.readString(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
            stream.writeString(about);
            stream.writeString(description);
        }
    }
