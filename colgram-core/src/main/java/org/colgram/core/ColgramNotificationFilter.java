package org.colgram.core;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, offline filter for posted Android notifications (Colemailer v3.4.19 port).
 * Cancels bundled group notifications only when every message in the notification batch is an ad.
 */
public final class ColgramNotificationFilter {

    private static final int MAX_TEXT = 8192;
    private static final int MAX_MESSAGES = 25;

    private static final Pattern HANDLE = p("(?<![\\w@])@[a-z0-9_]{4,32}\\b|https?://[^\\s]+|t\\.me/[^\\s]+");
    private static final Pattern SPACED = p("(?<![\\p{L}0-9_])(?:[а-яa-z][.\\-_*•|·~/\\\\]{1,2}){3,}[а-яa-z](?![\\p{L}0-9_])");
    private static final Pattern SPACED_WS = p("(?<![\\p{L}0-9_])(?:[а-я] ){3,}[а-я](?![\\p{L}0-9_])");
    private static final Pattern SPACED_STRIP = Pattern.compile("[\\s.\\-_*•|·~/\\\\]");
    private static final Pattern CYR_DIGIT_MIX = Pattern.compile("[а-я][036]+[а-я]");
    private static final Pattern AD_DISCLOSURE = p("(?:\\berid\\s*[:=]\\s*[a-z0-9_-]{3,}|(?<![\\p{L}0-9_])#(?:реклама|рекламный|промо|promo|ad|sponsored)(?![\\p{L}0-9_])|на\\s+правах\\s+рекламы)");
    private static final Pattern WORD = p("[0-9a-zа-я]+");
    private static final Pattern CYR = Pattern.compile("[а-я]");
    private static final Pattern LAT = Pattern.compile("[a-z]");
    private static final Pattern HOMOGLYPH_ONLY = Pattern.compile("^[aeopcxkymthbun]+$");
    private static final Pattern HOMOGLYPH_SPAM = p("^(?:ворк\\w*|курс\\w*|прайс\\w*|скуп\\w*|продам|продаю|продаем|куплю|мтс|крипт\\w*|звезд\\w*|акк\\w*|бонус\\w*|казик\\w*|казино|интим\\w*|дроп\\w*|обнал\\w*|выплат\\w*|гарант\\w*)$");

    private static final Pattern SALE = p("\\b(?:продаю|продам|продаем|продажа|скупаю|скупка|скупаем|предлагаю|предлагаем|сдам|сдаю|принимаю|принимаем|при[её]м\\s+(?:смс|номер\\w*|физ\\w*|акк\\w*)|выгодн\\w*\\s+(?:тем\\w*|услови\\w*|курс\\w*)|открыт\\s+к\\s+заказам|всегда\\s+на\\s+связи|пополн(?:ю|яю|ени\\w*)|в\\s+продаже|в\\s+наличии)\\b");
    private static final Pattern CATALOG = p("\\b(?:прайс\\w*|каталог\\w*|отзывы|баланс\\w*|гаранти[яию]|price\\s*list)\\b");
    private static final Pattern CONTACT = p("\\b(?:писать|пишите|пиши|заказать|заказам|купить|обращайтесь|контакты|в\\s+лс|в\\s+личку|в\\s+био)\\b");
    private static final Pattern PRICE = p("\\d+(?:[.,]\\d+)?\\s*(?:\\$|₽|€|руб\\w*|usd|ton|тон|звезд\\w*)|\\$\\s*\\d+");
    private static final Pattern WATERMARK = p("(?:отправленн?о|передано|переслано|опубликовано|рассылка|разослано|доставлено|запощено|sent|posted)\\s+(?:через|с\\s+помощью|при\\s+помощи|via|through|by)\\s+@[a-z0-9_]{4,32}\\b");
    private static final Pattern TARIFF_HEADER = p("\\b(?:репост\\w*|размещени\\w*|рассылк\\w*|закреп\\w*|тариф\\w*)\\s*:");
    private static final Pattern TARIFF_ROW = p("(?m)^[^\\p{L}0-9\\r\\n]{0,8}\\d+[ \\t]*(?:минут(?:а|ы)?|час(?:а|ов)?|сут(?:ки|ок)|д(?:ень|ня|ней))[ \\t]*[-–—:=][ \\t]*\\d+(?:[.,]\\d+)?[ \\t]*(?:\\$|₽|€|v|ton|руб\\w*|звезд\\w*)?[^\\p{L}0-9\\r\\n]*$");
    private static final Pattern AUTH = p("(?:my\\.telegram\\.org|login\\s*code|confirmation\\s*code|код\\s+(?:входа|подтверждения))");
    private static final Pattern RECRUIT = p("ищ[уе]\\w*\\s+(?:людей|сотрудник\\w*|работник\\w*)|набор\\s+в\\s+команду");
    private static final Pattern JOB_DETAIL = p("профит|график|обучение\\s*:\\s*бесплатно|трафик|под\\s+процент");
    private static final Pattern WORK_SIM = p("(?:\\b(?:ворк\\w*|заработ\\w*|зарабат\\w*|steam|стим\\w*|гифт\\w*|psn|xbox|cs2|cs:?go|valorant|валорант\\w*|физ(?:\\s*номер\\w*|\\s*сим\\w*|\\s*акк\\w*|ы|ов)?|е?-?сим\\w*|esim|симк\\w*|сим[-\\s]*карт\\w*|нерег\\w*|при[её]м\\s+смс|момент(?:альн\\w*)?\\s+оплат\\w*|выплат\\w*\\s+(?:сразу|момент\\w*|от)|гарант\\w*|репутац\\w*\\s+в\\s+тим\\w*|тим[аеу]\\s+(?:в\\s+био|у\\s+\\w+\\s+в\\s+био))\\b|(?<![\\w])\\+rep\\b)");
    private static final Pattern CARRIER = p("\\b(?:билайн\\w*|мтс|мегафон\\w*|теле2|tele2|йота|yota|сб[-\\s]*моб\\w*|сбермоб\\w*|т[-\\s]*мобайл\\w*|тинькофф\\s*моб\\w*)\\b");
    private static final Pattern SMM = p("\\b(?:клик(?:и|ов)|накрутк\\w*|залив|просмотр(?:ы|ов))\\b");
    private static final Pattern REFERRAL = p("(?:t\\.me|telegram\\.me)/[^\\s]+[?&]startapp=");
    private static final Pattern GIFT = p("nft|нфт|подар(?:ок|ки|ков)");
    private static final Pattern GIFT_CTA = p("бесплатно|хочешь\\s+улучшить|получить\\s+nft");
    private static final Pattern JOIN_CTA = p("\\b(?:присоединяй(?:ся|тесь)|заход(?:и|ите)|вступ(?:ай|айте)|залета(?:й|те))\\b");
    private static final Pattern DESTINATION = p("(?:t\\.me|telegram\\.me)/([a-z0-9_+/-]+)");

    private static final Set<String> SUPPORTED_PACKAGES = Set.of(
            "org.colgram.messenger.web",
            "org.colgram.messenger",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.telegram.plus",
            "org.thunderdog.challegram",
            "com.radolyn.ayugram",
            "com.exteragram.messenger"
    );

    private ColgramNotificationFilter() {}

    private static Pattern p(String expr) {
        return Pattern.compile(expr, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static boolean supportsPackage(String packageName) {
        return packageName != null && SUPPORTED_PACKAGES.contains(packageName);
    }

    static String foldHomoglyphs(String word) {
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            switch (c) {
                case 'a': c = 'а'; break;
                case 'e': c = 'е'; break;
                case 'o':
                case '0': c = 'о'; break;
                case 'p': c = 'р'; break;
                case 'c': c = 'с'; break;
                case 'x': c = 'х'; break;
                case 'y': c = 'у'; break;
                case 'k': c = 'к'; break;
                case 'm': c = 'м'; break;
                case 't': c = 'т'; break;
                case 'h': c = 'н'; break;
                case 'b': c = 'в'; break;
                case 'u': c = 'и'; break;
                case 'n': c = 'п'; break;
                case '3': c = 'з'; break;
                case '6': c = 'б'; break;
                default: break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String normalizeProse(String input) {
        String collapsed = SPACED.matcher(input).replaceAll(m -> SPACED_STRIP.matcher(m.group()).replaceAll(""));
        collapsed = SPACED_WS.matcher(collapsed).replaceAll(m -> m.group().replace(" ", ""));
        boolean hasCyr = CYR.matcher(collapsed).find();
        Matcher wm = WORD.matcher(collapsed);
        StringBuffer out = new StringBuffer(collapsed.length());
        while (wm.find()) {
            String w = wm.group();
            String rep = w;
            if (CYR.matcher(w).find() && (LAT.matcher(w).find() || CYR_DIGIT_MIX.matcher(w).find())) {
                rep = foldHomoglyphs(w);
            } else if (hasCyr && w.length() >= 3 && HOMOGLYPH_ONLY.matcher(w).matches()) {
                String cand = foldHomoglyphs(w);
                if (HOMOGLYPH_SPAM.matcher(cand).matches()) rep = cand;
            }
            wm.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        wm.appendTail(out);
        return out.toString();
    }

    private static int countDistinct(Pattern pat, String text) {
        HashSet<String> seen = new HashSet<>();
        Matcher m = pat.matcher(text);
        while (m.find()) seen.add(m.group());
        return seen.size();
    }

    public static boolean isAd(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_TEXT) return false;
        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (AUTH.matcher(text).find()) return false;
        if (AD_DISCLOSURE.matcher(text).find()) return true;
        boolean contact = HANDLE.matcher(text).find();
        String prose = normalizeProse(HANDLE.matcher(text).replaceAll(" "));
        boolean sale = SALE.matcher(prose).find();
        boolean catalogue = CATALOG.matcher(prose).find();
        boolean routing = CONTACT.matcher(prose).find();
        boolean price = PRICE.matcher(prose).find();
        boolean watermark = WATERMARK.matcher(text).find();

        if (contact && RECRUIT.matcher(prose).find()) {
            Matcher details = JOB_DETAIL.matcher(prose);
            int count = 0;
            while (details.find()) if (++count >= 2) return true;
        }
        if (contact && SMM.matcher(prose).find() && price && (routing || watermark)) return true;
        if (contact && REFERRAL.matcher(text).find() && GIFT.matcher(prose).find() && GIFT_CTA.matcher(prose).find()) return true;
        if (JOIN_CTA.matcher(prose).find()) {
            HashSet<String> destinations = new HashSet<>();
            Matcher links = DESTINATION.matcher(text);
            while (links.find()) {
                if (!destinations.add(links.group(1))) return true;
            }
        }
        if (contact && TARIFF_HEADER.matcher(prose).find()) {
            Matcher rows = TARIFF_ROW.matcher(prose);
            int count = 0;
            while (rows.find()) if (++count >= 3) return true;
        }
        int workSim = countDistinct(WORK_SIM, prose);
        int carriers = countDistinct(CARRIER, prose);
        if (contact && ((workSim >= 2 || (workSim >= 1 && carriers >= 2)) && (price || routing || catalogue)
                || (workSim >= 1 && price && routing))) {
            return true;
        }
        if (!contact && text.length() > 220) {
            boolean strongWall = (sale && (price || catalogue || workSim >= 1 || routing))
                    || (workSim >= 2 && (sale || price || catalogue || routing));
            if (strongWall) return true;
        }
        return contact && ((sale && (routing || catalogue || price || workSim >= 1))
                || (catalogue && routing && price)
                || (watermark && (sale || (catalogue && price))));
    }

    public static boolean shouldDismiss(String packageName, boolean enabled, boolean summary,
                                        boolean ongoing, boolean group, String[] messages) {
        if (!enabled || !supportsPackage(packageName) || summary || ongoing || !group
                || messages == null || messages.length == 0 || messages.length > MAX_MESSAGES) {
            return false;
        }
        for (String message : messages) {
            if (!isAd(message)) return false;
        }
        return true;
    }
}
