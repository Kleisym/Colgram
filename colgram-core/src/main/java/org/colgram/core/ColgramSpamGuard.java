package org.colgram.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded Kleisym/Colemailer v3.4.19 spam/ping detector and diagnostic engine.
 *
 * Operates completely in-process on the logged-in Colgram account (no Telethon/api_id required)
 * while maintaining the exact three-layer Colemailer detection pipeline:
 *   1. Structural hard evidence (stealth mentions, invisible ghost chars, inline-bot/autoposter signatures)
 *   2. NFKC + spaced-letter + homoglyph de-obfuscation + weighted topic/routing/catalogue scoring
 *   3. Capped conversational dampeners + buyer-lead veto (protecting real customer questions)
 */
public final class ColgramSpamGuard {

    public static final int VERDICT_OK = 0;
    public static final int VERDICT_MUTE = 1;
    public static final int VERDICT_DELETE = 2;

    public static final int SPAM_THRESHOLD = 50;

    public static final String PING_REPLY = "reply";
    public static final String PING_USERNAME = "username";
    public static final String PING_NAME = "name";
    public static final String PING_STEALTH = "stealth";
    public static final String PING_UNKNOWN = "unknown";

    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private static final int[][] FANCY_RANGES = {
            {0x1D400, 0x1D7FF}, {0xFF01, 0xFF5E}, {0x24B6, 0x24E9}, {0x1F130, 0x1F189}
    };

    private static final Pattern INVISIBLE_RE = Pattern.compile(
            "[\\u200b\\u200e\\u200f\\u2060-\\u2064\\u2066-\\u206f\\ufeff\\u180e\\u3164\\u115f\\u1160\\u2800]");

    private static final Pattern AUTH_SAFE_RE = p("(?:my\\.telegram\\.org|login\\s*code|confirmation\\s*code|код\\s+(?:входа|подтверждения))");
    private static final Pattern AD_DISCLOSURE_RE = p("(?:\\berid\\s*[:=]\\s*[a-z0-9_-]{3,}|(?<![\\p{L}0-9_])#(?:реклама|рекламный|промо|promo|ad|sponsored)(?![\\p{L}0-9_])|реклама\\s*[.,;]\\s*(?:ооо|ип|ао|пао|инн|erid)|инн\\s*\\d{10,12}\\b[^\\n]{0,40}\\berid\\b|маркировк\\w*\\s+реклам\\w*)");
    private static final Pattern AD_PROMO_PHRASE_RE = p("(?:на\\s+правах\\s+рекламы|партнерск\\w*\\s+(?:материал\\w*|интеграц\\w*|ссылк\\w*)|\\bреклам\\w*\\s+(?:пост\\w*|интеграц\\w*|услуг\\w*))");
    private static final Pattern CYR_RE = Pattern.compile("[а-я]");
    private static final Pattern LAT_RE = Pattern.compile("[a-z]");
    private static final Pattern WORD_RE = Pattern.compile("[0-9a-zа-я]+");
    private static final Pattern MASK_RE = p("(?:https?://\\S+|t\\.me/\\S+|telegram\\.me/\\S+|@[a-z0-9_]{3,32})");
    private static final Pattern SPACED_RE = p("(?<![\\p{L}0-9_])(?:[а-яa-z][.\\-_*•|·~/\\\\]{1,2}){3,}[а-яa-z](?![\\p{L}0-9_])");
    private static final Pattern SPACED_WS_RE = p("(?<![\\p{L}0-9_])(?:[а-я] ){3,}[а-я](?![\\p{L}0-9_])");
    private static final Pattern SPACED_STRIP_RE = Pattern.compile("[\\s.\\-_*•|·~/\\\\]");
    private static final Pattern CYR_DIGIT_MIX_RE = Pattern.compile("[а-я][036]+[а-я]");
    private static final Pattern TG_USER_URL_RE = Pattern.compile("tg://user\\?id=(\\d+)");
    private static final Pattern HAS_LETTER_RE = Pattern.compile("\\p{L}");
    private static final Pattern HAS_DIGIT_RE = Pattern.compile("\\d");
    private static final Pattern HANDLE_STRIP_RE = Pattern.compile("@[A-Za-z0-9_]{3,32}");
    private static final Pattern NON_WORD_STRIP_RE = Pattern.compile("[\\W_]+");
    private static final Pattern NON_ALNUM_CYR_RE = Pattern.compile("[^0-9a-zа-яё]");
    private static final Pattern HOMOGLYPH_LAT_ONLY_RE = Pattern.compile("^[aeopcxkymthbun]+$");
    private static final Pattern HOMOGLYPH_DECEPTIVE_CYR_RE = p(
            "^(?:ворк\\w*|курс\\w*|прайс\\w*|скуп\\w*|продам|продаю|продаем|куплю|мтс|"
            + "крипт\\w*|звезд\\w*|акк\\w*|бонус\\w*|казик\\w*|казино|интим\\w*|дроп\\w*|обнал\\w*|выплат\\w*|гарант\\w*)$");

    private static final Pattern[] SPAM_SIGNATURES = {
            p("(?:отправленн?о|передано|рассылаю|рассылка|рассылается|разослано|доставлено|опубликовано|запощено|размещено|постинг|автопост\\w*)\\s+(?:с\\s+помощью|при\\s+помощи|через|в|с)\\s+@?[a-z0-9_]{3,32}\\b"),
            p("\\b(?:sent|posted|broadcasted|powered)\\s+(?:via|by|with|through)\\s+@?[a-z0-9_]{3,32}\\b"),
            p("(?:скрипт|софт|программ\\w*|бот)\\s+(?:для\\s+)?(?:рассыл|спам|автопост|инвайт)\\w*\\s*[:\\-—]?\\s*@?\\w+"),
            p("@[a-z0-9_]*(?:autopost|sender|spambot|postbot|mailer|repost|trafficbot|mailing)[a-z0-9_]*"),
            p("заказать\\s+(?:рассылку|спам|инвайтинг)")
    };

    private static final class LexiconRule {
        final Pattern pattern;
        final int points;
        final String label;
        final boolean strong;

        LexiconRule(String regex, int points, String label, boolean strong) {
            this.pattern = p(regex);
            this.points = points;
            this.label = label;
            this.strong = strong;
        }
    }

    private static final LexiconRule[] SPAM_LEXICON = {
            new LexiconRule("\\bзвезд\\w*|\\bstars?\\b|\\bzvezd\\w*", 20, "stars", false),
            new LexiconRule("\\bтон\\w{0,2}\\b|\\bton\\b|\\busdt\\b|\\bbtc\\b|\\beth\\b|крипт\\w*|\\bp2p\\b|обменник\\w*|биткоин\\w*|\\bbitcoin\\w*|\\bcrypto\\w*|\\bairdrop\\w*|\\bтокен\\w*|\\btokens?\\b", 20, "crypto", false),
            new LexiconRule("\\bнфт\\b|\\bnft\\w*|подар(?:ок|ки|ков|ками|ке|очки)\\b|\\bфлор\\w*|\\bgifts?\\b", 20, "nft_gifts", false),
            new LexiconRule("\\bголд\\w*|\\bgold\\b|\\bробукс\\w*|\\bгемы\\b|\\bдонат\\w*", 15, "game_currency", false),
            new LexiconRule("аккаунт\\w*|\\bакк(?:и|а|ов)?\\b|\\bсессии\\b|\\btdata\\b|виртуальн\\w*\\s+номер\\w*|\\bпремиум\\w*|\\bpremium\\b|\\bпремк\\w*|\\bбуст(?:ы|ов)?\\b|\\busernames?\\b", 20, "tg_goods", false),
            new LexiconRule("\\bфиз(?:\\s*номер\\w*|\\s*сим\\w*|\\s*акк\\w*|ы|ов)?\\b|\\bе?-?сим(?:ки|ок|ка)?\\b|\\besim\\b|\\bсимк\\w*|\\bсим[-\\s]*карт\\w*|\\bнерег\\w*|\\bпод\\s+рег\\w*|\\bпри[её]м\\s+смс\\b|\\bотлеж\\w*\\b", 25, "sim_numbers", false),
            new LexiconRule("заработ\\w*(?:\\s+(?:в\\s+день|от\\s+\\d+|онлайн|на\\s+крипт\\w*))?|зарабат\\w*|криптосигнал\\w*|сигнал\\w*\\s+(?:на\\s+)?(?:фьючерс\\w*|трейд\\w*|крипт\\w*|бинар\\w*)|памп\\w*\\s+канал\\w*|вип\\s*сигнал\\w*|\\bворк\\w*|\\bдоход\\w*(?:\\s+от\\s+\\d+)?|пассивн\\w*|без\\s+вложени\\w*|подработк\\w*|удаленн\\w*\\s+работ\\w*|\\bваканси(?:я|и)\\b|\\bтребу(?:ю|е)тся\\b|набор\\s+в\\s+команду|момент(?:альн\\w*)?\\s+оплат\\w*|выплат\\w*\\s+(?:от|до|сразу|момент\\w*|кажд\\w*)|выгодн\\w*\\s+(?:тем\\w*|услови\\w*|курс\\w*)|репутац\\w*\\s+в\\s+тим\\w*|(?<!\\w)\\+rep\\b|\\bпрофит\\w*|инвест\\w*|\\bearn\\w*|\\bprofit\\w*|\\binvest\\w*|\\bсхем[ауы]\\b|\\bарбитраж\\w*", 35, "earnings", true),
            new LexiconRule("казино|\\bcasino\\b|\\bkazino\\b|\\bказик\\w*|гемблинг|\\bazart\\b|\\b1win\\b|\\bstake\\b|ставк[иа]\\b(?:\\s+на\\s+спорт)?|букмекер\\w*|фриспин\\w*|промокод\\w*|джекпот\\w*|рулетк\\w*|прогноз\\w*\\s+на\\s+(?:спорт|матч)|\\bслоты\\b|\\bbet\\b|\\bбеттинг\\w*", 35, "gambling", true),
            new LexiconRule("\\bинтим\\w*|\\b18\\s*\\+|\\bэро\\b|\\bслив\\w*|\\bприват\\w*\\s+канал|\\bonlyfans\\b|\\bнюдс\\w*|\\bnudes?\\b", 35, "adult", true),
            new LexiconRule("\\bклик(?:и|ов)\\b|\\bпросмотр(?:ы|ов)\\b\\s+(?:на|для)|\\bнакрутк\\w*|\\bподписчик(?:и|ов)\\b\\s+(?:на|в|для)", 20, "smm", false),
            new LexiconRule("\\bвербовк\\w*|\\bдроп\\w*\\b|\\bобнал\\w*|\\bкарты?\\s+(?:физ|юр)", 30, "fraud", true),
            new LexiconRule("выиграл\\w*|выигрыш\\w*|\\bприз\\w*|розыгрыш\\w*|халяв\\w*|\\bраздач\\w*|\\bраздаю\\b|бесплатн\\w*|\\bgiveaway\\w*|\\bбонус\\w*|получи(?:ть|те)?\\s+(?:приз|бонус|подарок|деньги)", 25, "prize", false),
            new LexiconRule("пиар\\w*\\s+в\\s+\\d+|\\b\\d+\\s+чат(?:ах|ов|а)\\b|прода(?:м|ю)\\s+рекламу\\s+в\\s+(?:чат|канал|\\d)|взаимн\\w*\\s+пиар\\w*|\\bвп\\b|рассылк\\w*\\s+по\\s+(?:чатам|лс|базе)|\\bинвайт\\w*|\\bрепост(?:ы|ов)?\\s*:|\\bкуплю\\s+чат\\w*", 25, "ad_services", false),
            new LexiconRule("делаю\\s+(?:ботов|бота|сайт\\w*|дизайн\\w*|аватар\\w*|монтаж\\w*|рекламу)|на\\s+заказ\\b|\\bуслуг\\w*|разработ\\w*\\s+(?:бот|сайт)\\w*", 20, "services", false),
            new LexiconRule("\\b(?:мой|мою|наш|нашу)\\s+(?:канал\\w*|чат\\w*|бот\\w*|магазин\\w*|проект\\w*|групп\\w*)|\\bподпис(?:ывайся|ывайтесь|ись|итесь)\\b|\\bжми\\s+(?:сюда|ниже|на\\s+кнопку|по\\s+кнопке)\\b|\\bпереходи\\s+(?:сюда|по\\s+ссылке|в\\s+канал|в\\s+бот)\\b|\\bуспей\\s+(?:забрать|подписаться|войти)\\b|\\bзаход(?:и|ите)\\b|\\bвступ(?:ай|айте)\\b|\\bзалета(?:й|йте)\\b|\\bjoin\\s+(?:my|our|us)\\b|\\bsubscribe\\b", 25, "self_promo", false)
    };

    private static final Set<String> BUYER_BLOCKING_TOPICS = Set.of(
            "earnings", "gambling", "adult", "fraud", "prize", "self_promo",
            "services", "crypto", "nft_gifts", "tg_goods", "sim_numbers"
    );

    private static final Pattern CARRIER_RE = p("\\b(?:билайн\\w*|мтс|мегафон\\w*|теле2|tele2|йота|yota|сб[-\\s]*моб\\w*|сбермоб\\w*|т[-\\s]*мобайл\\w*|тинькофф\\s*моб\\w*|ростелеком\\w*)\\b");
    private static final Pattern PROMO_RE = p("\\b(?:бонус\\w*|промокод\\w*|купон\\w*|к[еэ]шб[еэ]к\\w*|акци[яи]|скидк\\w*|бесплатн\\w*|халяв\\w*|розыгрыш\\w*|раздач\\w*|выигр\\w*|вывод\\w*|оптом|опт\\b)");
    private static final Pattern SALE_VERB_RE = p("\\b(?:продам|продаю|продается|продаем|продажа|скупаю|скупка|скупаем|куплю|покупаю|обменяю|обмен|сдам|сдаю|предлагаю|предлагаем|оказываю|оказываем|отдам|отдаю|дарю|раздаю|раздача|делаю|помогу|ищу\\s+менеджер\\w*|ищ(?:у|ем)\\s+(?:партнер\\w*|админ\\w*|людей|сотрудник\\w*|работник\\w*)|требу(?:ю|е)тся|нужны\\s+люди|принима(?:ю|ем)|при[её]м\\s+(?:смс|номер\\w*|физ\\w*|акк\\w*)|возьму\\s+(?:под|на\\s+ворк|на\\s+рег\\w*|физ\\w*|сим\\w*|акк\\w*)|момент(?:альн\\w*)?\\s+оплат\\w*|выплат\\w*\\s+(?:момент\\w*|сразу|от\\b)|выгодн\\w*\\s+(?:тем\\w*|услови\\w*|курс\\w*)|обращайтесь|дешевле|дешево|выгодно|недорого|по\\s+курсу|ниже\\s+рынка|купить\\s+можно|в\\s+наличии|цена\\s+за|прайс\\s*:|sell\\w*|buy\\w*|cheap\\w*)\\b");
    private static final Pattern SELLER_ASK_RE = p("\\b(?:купишь|купите|покупайте|покупай|закажи|заказывай|нужн[ыао]\\s+(?:звезд|акк|подписчик|просмотр|клик|реклам|бусты|премиум)|хочешь\\s+(?:заработать|зарабатывать|звезд|бесплатн|подарок|подарки|премиум)|хотите\\s+(?:заработать|зарабатывать))");
    private static final Pattern ROUTING_RE = p("(?:пиш(?:и|ите)|писать|связь|обращаться|отпиш(?:и|ите)|стучи(?:те)?|жду|заказ|менеджер\\w*|contact|для\\s+заказа|купить)\\s*(?:в\\s+лс|в\\s+личку|в\\s+пм|сюда|тут|мне)?\\s*[:\\-—–]?\\s*@\\w+");
    private static final Pattern EMOJI_ROUTING_RE = p("(?:📩|👉|📲|💬|✍️|✍|✉️|☎️|📞|➡️|⬇️)\\s*(?:в\\s+лс|пишите|связь|обращаться)?\\s*@\\w+");
    private static final Pattern DM_CTA_RE = p("(?:\\bв\\s+лс\\b|\\bв\\s+личк\\w*|\\bв\\s+пм\\b|\\bлс\\s+открыт\\w*|\\bпиш(?:и|ите)\\b|\\bнапиш(?:и|ите)\\b|\\bотпиш(?:и|ите)\\w*|\\bстуч(?:и|ите)\\b|подробн\\w*|ссылка\\s+в\\s+(?:профиле|био|описании)|\\bв\\s+био\\b|\\bпо\\s+ссылке\\b|жми(?:\\s+сюда)?\\b|кликай\\w*|переходи\\w*|\\bтык(?:ай)?\\b|\\bуспей\\b|\\bdm\\b|\\bpm\\b|\\bwrite\\s+me\\b|\\bcontact\\b)");
    private static final Pattern PRICE_RE = p("\\d+(?:[.,]\\d+)?\\s*(?:₽|руб\\w*|\\$|usd\\w*|€|грн|тг|⭐|🌟|звезд\\w*|ton\\b|тон\\b)|\\$\\s*\\d+");
    private static final Pattern LINK_RE = p("t\\.me/(?:\\+|joinchat/|[a-z0-9_]{5,})|telegram\\.me/|https?://\\S+");
    private static final Pattern PRIVATE_LINK_RE = p("t\\.me/(?:\\+|joinchat/)|t\\.me/[a-z0-9_]*bot\\b|\\?start=");
    private static final Pattern DEST_RE = p("(?:https?://)?(?:t\\.me|telegram\\.me)/([a-z0-9_+/-]+)");
    private static final Pattern HANDLE_RE = p("(?<![\\w@])@([a-zA-Z0-9_]{4,32})");
    private static final Pattern CONVO_RE = p("\\b(?:я|мне|меня|мы|нас|ты|тебе|тебя|привет|ку|здравствуйте|спасибо|спс|лол|хах\\w*|ок|понял\\w*|ага|угу|да|нет|тоже)\\b");
    private static final Pattern CATALOG_PRICE_ROW_RE = p("(?:\\(\\+?\\d{1,4}\\)|\\b(?:19|20)\\d{2}\\b|\\b\\d+\\s*(?:минут(?:а|ы)?|час(?:а|ов)?|сут(?:ки|ок)|д(?:ень|ня|ней))\\b|[а-яa-z]{3,})[^\\n]{0,60}?\\s[-–—:=]\\s*\\d+(?:[.,]\\d+)?\\s*(?:[⭐🌟Ⓥ]|\\bv\\b|звезд\\w*|₽|руб\\w*|р\\b|ton\\b|тон\\b|\\$|€)?\\s*$");
    private static final Pattern SELLER_HEADER_RE = p("\\b(?:в\\s+продаже|прайс(?:[- ]лист)?|актуальн\\w*\\s+(?:цен\\w*|прайс\\w*)|наличие|каталог|price\\s*list|(?:репост\\w*|размещени\\w*|тариф\\w*)\\s*:)");
    private static final Pattern CONTACT_SECTION_RE = p("\\b(?:купить|заказать|отзывы|обратная\\s+связь|для\\s+заказа|контакты|reviews|order|contact)\\b");
    private static final Pattern JOIN_PROMO_RE = p("\\b(?:присоединяй(?:ся|тесь)|заход(?:и|ите)|вступ(?:ай|айте)|залета(?:й|те))\\b");
    private static final Pattern CATALOG_BULLET_RE = Pattern.compile("^(?:[·•▪▫▪◾◽\\-—\\*✅✔☑🔹🔸🔵🟢❗❕]|\\d+[.\\)\\-—]|[1-9]️⃣)\\s*");
    private static final Pattern BANNER_RE = Pattern.compile("^(?:🔥|⚡|📢|📣|💎|💰|💸|💵|🚀|🚨|❗|‼|⚠|⭐|🌟|💥|🎯|👉|📩|📲|💬|🎁|🤑|📈|🆕|🔝)");
    private static final Pattern ASSET_SPEC_RE = p("\\b(?:канал[ыа]?|чат[ыа]?|групп[уыа]|сетк[уи]|баз[уы])\\b");
    private static final Pattern BUYER_PURCHASE_RE = p("\\b(?:куплю|купить|покупаю|возьму)\\s+(?:у\\s+тебя\\s+|у\\s+вас\\s+)?(?:\\d+\\s+)?(?:рекламу|пост\\w*|закреп\\w*|место|слот\\w*|размещени\\w*)");
    private static final Pattern BARE_BUYER_Q_RE = p("\\b(?:реклам\\w*|пост\\w*|канал\\w*|чат\\w*|размещ\\w*|закреп\\w*|стоит|цен\\w*)\\b");

    private static final Pattern[] BUYER_PATTERNS = {
            p("\\bпочем\\b"), p("\\bцен[ау]\\b"), p("\\bактуально\\b"), p("\\bсвободн\\w*\\b"),
            p("\\bстат[уа]\\b"), p("\\bстатистик[ау]\\b"), p("\\bохват\\w*\\b"), p("\\bчекни\\s+лс\\b"),
            p("\\bответь(?:те)?\\s+в\\s+лс\\b"), p("\\bкуплю\\s+рекламу\\b"), p("\\bхочу\\s+купить\\b"),
            p("\\bпрода(?:шь|ешь|ете)\\b"), p("\\bсколько\\s+стоит\\b"),
            p("\\bможно\\s+(?:купить|взять|разместить|заказать)\\b"), p("\\b(?:место\\s+есть|есть\\s+место)\\b"),
            p("\\bзакреп\\w*\\b"), p("\\bслот\\w*\\b")
    };

    private static final String[] COUNTRY_FLAGS = {
            "🇺🇸", "🇮🇳", "🇮🇩", "🇨🇴", "🇲🇲", "🇧🇩", "🇧🇷", "🇨🇦", "🇲🇽", "🇹🇷", "🇨🇱", "🇿🇦",
            "🇷🇺", "🇺🇦", "🇰🇿", "🇧🇾", "🇺🇿", "🇬🇧", "🇩🇪", "🇵🇭", "🇻🇳", "🇳🇬", "🇵🇰", "🇪🇬"
    };

    public static final class NormalizedResult {
        public final String normalized;
        public final Set<String> flags;

        public NormalizedResult(String normalized, Set<String> flags) {
            this.normalized = normalized;
            this.flags = Collections.unmodifiableSet(flags);
        }
    }

    public static final class MentionTarget {
        public final long targetUserId;
        public final String targetUrl;
        public final int offset;
        public final int length;

        public MentionTarget(long targetUserId, String targetUrl, int offset, int length) {
            this.targetUserId = targetUserId;
            this.targetUrl = targetUrl == null ? "" : targetUrl;
            this.offset = offset;
            this.length = length;
        }
    }

    public static final class SpamReport {
        public final int score;
        public final boolean isSpam;
        public final boolean shouldAutoRead;
        public final boolean isHardSpam;
        public final boolean isExplicitAd;
        public final boolean isLead;
        public final String primaryTrigger;
        public final List<String> factors;
        public final Set<String> obfuscationFlags;
        public final String normalizedText;

        public SpamReport(int score, boolean isSpam, boolean shouldAutoRead, boolean isHardSpam,
                          boolean isLead, String primaryTrigger, List<String> factors,
                          Set<String> obfuscationFlags, String normalizedText) {
            this(score, isSpam, shouldAutoRead, isHardSpam, false, isLead,
                    primaryTrigger, factors, obfuscationFlags, normalizedText);
        }

        public SpamReport(int score, boolean isSpam, boolean shouldAutoRead, boolean isHardSpam,
                          boolean isExplicitAd, boolean isLead, String primaryTrigger, List<String> factors,
                          Set<String> obfuscationFlags, String normalizedText) {
            this.score = score;
            this.isSpam = isSpam;
            this.shouldAutoRead = shouldAutoRead;
            this.isHardSpam = isHardSpam;
            this.isExplicitAd = isExplicitAd;
            this.isLead = isLead;
            this.primaryTrigger = primaryTrigger;
            this.factors = Collections.unmodifiableList(factors);
            this.obfuscationFlags = Collections.unmodifiableSet(obfuscationFlags);
            this.normalizedText = normalizedText;
        }

        public String formatDiagnostic(long senderId, String pingKind, boolean muteActive) {
            StringBuilder sb = new StringBuilder();
            sb.append("Реклама: ").append(shouldAutoRead || isSpam ? "да" : "нет")
                    .append("; балл: ").append(score);
            if (isLead) sb.append(" (живой лид / вопрос покупателя)");
            sb.append("\nФакторы: ");
            if (factors.isEmpty()) {
                sb.append("нет");
            } else {
                int lim = Math.min(8, factors.size());
                for (int i = 0; i < lim; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(factors.get(i));
                }
            }
            if (!obfuscationFlags.isEmpty()) {
                sb.append("\nМаскировка: ").append(String.join(", ", obfuscationFlags));
            }
            sb.append("\nОтправитель: ").append(senderId);
            sb.append("\nАдресный пинг: ").append(pingKind != null ? pingKind : "нет");
            sb.append("; Мьют активен: ").append(muteActive ? "да" : "нет");
            return sb.toString();
        }
    }

    private static final class SenderState {
        long windowStart;
        int score;
        int messages;
    }

    private static final ConcurrentHashMap<Long, SenderState> senders = new ConcurrentHashMap<>();

    private ColgramSpamGuard() {}

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public static boolean hasInvisibleGhostChars(String text) {
        return text != null && INVISIBLE_RE.matcher(text).find();
    }

    /** NFKC + spaced-letter collapse + lookalike homoglyph folding while keeping URLs/@handles intact. */
    public static NormalizedResult normalizeText(String raw) {
        Set<String> flags = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return new NormalizedResult("", flags);

        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            for (int[] range : FANCY_RANGES) {
                if (cp >= range[0] && cp <= range[1]) {
                    flags.add("fancy_font");
                    break;
                }
            }
            i += Character.charCount(cp);
        }

        String t = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('ё', 'е');
        Matcher spacedM = SPACED_RE.matcher(t);
        StringBuffer sb1 = new StringBuffer(t.length());
        while (spacedM.find()) {
            flags.add("spaced_letters");
            spacedM.appendReplacement(sb1, Matcher.quoteReplacement(
                    SPACED_STRIP_RE.matcher(spacedM.group(0)).replaceAll("")));
        }
        spacedM.appendTail(sb1);
        t = sb1.toString();

        Matcher spacedWsM = SPACED_WS_RE.matcher(t);
        StringBuffer sb2 = new StringBuffer(t.length());
        while (spacedWsM.find()) {
            flags.add("spaced_letters");
            spacedWsM.appendReplacement(sb2, Matcher.quoteReplacement(spacedWsM.group(0).replace(" ", "")));
        }
        spacedWsM.appendTail(sb2);
        t = sb2.toString();

        boolean hasCyrContext = CYR_RE.matcher(t).find();
        StringBuilder out = new StringBuilder(t.length());
        int pos = 0;
        Matcher maskM = MASK_RE.matcher(t);
        while (maskM.find()) {
            out.append(foldWords(t.substring(pos, maskM.start()), flags, hasCyrContext));
            out.append(maskM.group(0));
            pos = maskM.end();
        }
        out.append(foldWords(t.substring(pos), flags, hasCyrContext));
        return new NormalizedResult(out.toString(), flags);
    }

    private static String foldWords(String chunk, Set<String> flags, boolean hasCyrContext) {
        if (chunk.isEmpty()) return chunk;
        Matcher wm = WORD_RE.matcher(chunk);
        StringBuffer out = new StringBuffer(chunk.length());
        while (wm.find()) {
            String w = wm.group();
            String rep = w;
            boolean hasCyr = CYR_RE.matcher(w).find();
            if (hasCyr && (LAT_RE.matcher(w).find() || CYR_DIGIT_MIX_RE.matcher(w).find())) {
                flags.add("mixed_script");
                rep = ColgramNotificationFilter.foldHomoglyphs(w);
            } else if (hasCyrContext && w.length() >= 3 && HOMOGLYPH_LAT_ONLY_RE.matcher(w).matches()) {
                String folded = ColgramNotificationFilter.foldHomoglyphs(w);
                if (HOMOGLYPH_DECEPTIVE_CYR_RE.matcher(folded).matches()) {
                    flags.add("mixed_script");
                    rep = folded;
                }
            }
            wm.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        wm.appendTail(out);
        return out.toString();
    }

    /** Safely slices a string by UTF-16 code-unit offset/length (Telegram TL entities). */
    public static String utf16Slice(String text, int offset, int length) {
        if (text == null || offset < 0 || length <= 0 || offset >= text.length()) return "";
        int end = Math.min(text.length(), offset + length);
        return text.substring(offset, end);
    }

    /**
     * Classifies whether mention entities perform a stealth ping (pointing to selfUserId under
     * emoji/space/unrelated text) or mass-mention spam (>= 4 mentions or hidden non-alphanumeric tag).
     */
    public static String classifyEntityPing(String text, List<MentionTarget> entities,
                                            long selfUserId, Collection<String> ownLabels) {
        if (entities == null || entities.isEmpty()) return null;
        Set<String> cleanOwn = new HashSet<>();
        if (ownLabels != null) {
            for (String l : ownLabels) {
                String norm = lettersOnly(l);
                if (!norm.isEmpty()) cleanOwn.add(norm);
            }
        }
        String kind = null;
        for (MentionTarget ent : entities) {
            long target = ent.targetUserId;
            if (target == 0 && !ent.targetUrl.isEmpty()) {
                Matcher um = TG_USER_URL_RE.matcher(ent.targetUrl.toLowerCase(Locale.ROOT));
                if (um.find()) {
                    try { target = Long.parseLong(um.group(1)); } catch (NumberFormatException ignored) {}
                }
            }
            if (target != 0 && selfUserId != 0 && target == selfUserId) {
                String rawSlice = utf16Slice(text, ent.offset, ent.length);
                String label = lettersOnly(rawSlice);
                boolean matchesSelf = false;
                if (!label.isEmpty()) {
                    for (String own : cleanOwn) {
                        if (label.contains(own) || (label.length() >= 3 && own.contains(label))) {
                            matchesSelf = true;
                            break;
                        }
                    }
                }
                if (!matchesSelf) return PING_STEALTH;
                kind = rawSlice.trim().startsWith("@") ? PING_USERNAME : PING_NAME;
            }
        }
        return kind;
    }

    public static boolean hasHiddenOrMassMentionEntity(String text, List<MentionTarget> entities) {
        if (entities == null || entities.isEmpty()) return false;
        int totalMentions = 0;
        int userMentions = 0;
        for (MentionTarget ent : entities) {
            boolean isUserRef = ent.targetUserId != 0
                    || ent.targetUrl.toLowerCase(Locale.ROOT).contains("tg://user?id=");
            if (isUserRef) {
                userMentions++;
                totalMentions++;
                String slice = utf16Slice(text, ent.offset, ent.length);
                if (!HAS_LETTER_RE.matcher(slice).find() && !HAS_DIGIT_RE.matcher(slice).find()) {
                    return true;
                }
            } else {
                totalMentions++;
            }
        }
        return userMentions >= 2 || totalMentions >= 4;
    }

    public static boolean isGenuineBuyerQuestion(String text, Collection<String> ownUsernames) {
        if (text == null || text.isEmpty()) return false;
        if (hasInvisibleGhostChars(text)) return false;
        NormalizedResult nr = normalizeText(text);
        String clean = nr.normalized.trim();
        if (clean.length() >= 220 || !nr.flags.isEmpty()) return false;
        if (ROUTING_RE.matcher(clean).find() || EMOJI_ROUTING_RE.matcher(clean).find()) return false;

        Set<String> own = new HashSet<>();
        if (ownUsernames != null) {
            for (String u : ownUsernames) {
                if (u != null) own.add(u.toLowerCase(Locale.ROOT).replace("@", ""));
            }
        }
        Matcher hm = HANDLE_RE.matcher(text);
        while (hm.find()) {
            if (!own.contains(hm.group(1).toLowerCase(Locale.ROOT))) return false;
        }
        if (LINK_RE.matcher(clean).find()) return false;
        if (countCountryFlags(text) >= 2) return false;

        String prose = MASK_RE.matcher(clean).replaceAll(" ");
        if (SELLER_ASK_RE.matcher(prose).find()) return false;

        boolean buyerPurchase = BUYER_PURCHASE_RE.matcher(prose).find();
        String withoutBuyer = BUYER_PURCHASE_RE.matcher(prose).replaceAll(" ");
        if (SALE_VERB_RE.matcher(withoutBuyer).find()) return false;

        Set<String> hitTopics = new HashSet<>();
        for (LexiconRule rule : SPAM_LEXICON) {
            if (rule.pattern.matcher(prose).find()) hitTopics.add(rule.label);
        }
        if (countDistinctMatches(CARRIER_RE, prose) >= 2) hitTopics.add("carriers");
        if (hitTopics.size() >= 2) return false;
        if (!buyerPurchase) {
            for (String t : hitTopics) {
                if (BUYER_BLOCKING_TOPICS.contains(t)
                        && !"stars".equals(t) && !"crypto".equals(t)
                        && !"tg_goods".equals(t) && !"nft_gifts".equals(t)) {
                    return false;
                }
            }
        }
        boolean hasQ = clean.contains("?");
        boolean hasTrigger = buyerPurchase;
        if (!hasTrigger) {
            for (Pattern p : BUYER_PATTERNS) {
                if (p.matcher(prose).find()) {
                    hasTrigger = true;
                    break;
                }
            }
        }
        if (hasTrigger && (hasQ || clean.length() < 120)) return true;
        return hasQ && clean.length() < 120 && hitTopics.isEmpty() && BARE_BUYER_Q_RE.matcher(clean).find();
    }

    public static SpamReport diagnose(String rawText) {
        return diagnose(rawText, "", null, false, false, false, false, false, Collections.emptyList(), false, false);
    }

    public static SpamReport diagnose(String rawText, String metadata, String pingKind, boolean isPing,
                                      boolean viaBot, boolean visualMedia, boolean hiddenOrMassEntity,
                                      boolean isLeadCheckAllowed, Collection<String> ownUsernames) {
        return diagnose(rawText, metadata, pingKind, isPing, viaBot, visualMedia, hiddenOrMassEntity,
                isLeadCheckAllowed, ownUsernames, false, false);
    }

    public static SpamReport diagnose(String rawText, String metadata, String pingKind, boolean isPing,
                                      boolean viaBot, boolean visualMedia, boolean hiddenOrMassEntity,
                                      boolean isLeadCheckAllowed, Collection<String> ownUsernames,
                                      boolean hasInlineUrlButtons, boolean isForwardedFromChannel) {
        String text = rawText == null ? "" : rawText;
        String meta = metadata == null ? "" : metadata;
        NormalizedResult combined = normalizeText(meta.isEmpty() ? text : (text + "\n" + meta));
        List<String> factors = new ArrayList<>();

        if (AUTH_SAFE_RE.matcher(combined.normalized).find()) {
            return new SpamReport(0, false, false, false, false, false, "auth_safe",
                    Collections.singletonList("telegram_auth_code"), combined.flags, combined.normalized);
        }

        if (PING_STEALTH.equals(pingKind)) {
            return new SpamReport(100, true, true, true, false, false, "stealth_ping",
                    Collections.singletonList("hidden_mention_without_username_or_reply"),
                    combined.flags, combined.normalized);
        }
        if (viaBot) {
            return new SpamReport(100, true, true, true, false, false, "sent_via_inline_bot",
                    Collections.singletonList("inline_bot"), combined.flags, combined.normalized);
        }
        if (hasInvisibleGhostChars(text)) {
            return new SpamReport(100, true, true, true, false, false, "invisible_ghost_ping",
                    Collections.singletonList("zero_width_ghost_chars"), combined.flags, combined.normalized);
        }
        if (hiddenOrMassEntity) {
            return new SpamReport(100, true, true, true, false, false, "hidden_or_mass_entity",
                    Collections.singletonList("entity_ghost_or_mass_mention"), combined.flags, combined.normalized);
        }
        String lowText = text.toLowerCase(Locale.ROOT);
        for (Pattern sig : SPAM_SIGNATURES) {
            if (sig.matcher(lowText).find()) {
                return new SpamReport(100, true, true, true, false, false, "signature_match",
                        Collections.singletonList("autoposter_signature"), combined.flags, combined.normalized);
            }
        }

        if (text.isEmpty() && meta.isEmpty() && !hasInlineUrlButtons) {
            return new SpamReport(0, false, false, false, false, false, "",
                    Collections.emptyList(), combined.flags, "");
        }

        String prose = MASK_RE.matcher(combined.normalized).replaceAll(" ");
        String[] rawLines = text.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            String trimmed = l.trim();
            if (!trimmed.isEmpty()) lines.add(trimmed);
        }

        int score = 0;
        boolean explicitAd = AD_DISCLOSURE_RE.matcher(combined.normalized).find();
        if (explicitAd) {
            score += 65;
            factors.add("ad_disclosure(+65)");
        }
        boolean adPromoPhrase = AD_PROMO_PHRASE_RE.matcher(prose).find();
        if (adPromoPhrase) {
            score += 35;
            factors.add("ad_promo_phrase(+35)");
        }
        if (combined.flags.contains("mixed_script")) { score += 30; factors.add("lookalike_letters(+30)"); }
        if (combined.flags.contains("fancy_font")) { score += 25; factors.add("fancy_unicode_font(+25)"); }
        if (combined.flags.contains("spaced_letters")) { score += 25; factors.add("spaced_out_letters(+25)"); }

        int topicPoints = 0;
        boolean strongTopic = false;
        boolean multiHitTopic = false;
        Set<String> topics = new HashSet<>();
        for (LexiconRule rule : SPAM_LEXICON) {
            int hits = countDistinctMatches(rule.pattern, prose);
            if (hits == 0) continue;
            topics.add(rule.label);
            if (rule.strong) strongTopic = true;
            int extra = Math.min(10 * (hits - 1), 20);
            if (extra > 0) multiHitTopic = true;
            topicPoints += rule.points + extra;
            factors.add("topic_" + rule.label + "(+" + (rule.points + extra) + ")");
        }

        boolean hasSaleVerb = SALE_VERB_RE.matcher(BUYER_PURCHASE_RE.matcher(prose).replaceAll(" ")).find();
        boolean hasSellerAsk = SELLER_ASK_RE.matcher(prose).find();

        Set<String> own = new HashSet<>();
        if (ownUsernames != null) {
            for (String u : ownUsernames) {
                if (u != null) own.add(u.toLowerCase(Locale.ROOT).replace("@", ""));
            }
        }
        List<String> foreignHandles = new ArrayList<>();
        Matcher hm = HANDLE_RE.matcher(text);
        while (hm.find()) {
            String h = hm.group(1);
            if (!own.contains(h.toLowerCase(Locale.ROOT))) foreignHandles.add(h);
        }

        boolean hasEmojiRouting = EMOJI_ROUTING_RE.matcher(combined.normalized).find();
        boolean hasTextRouting = ROUTING_RE.matcher(combined.normalized).find() && !foreignHandles.isEmpty();
        boolean hasDmCta = DM_CTA_RE.matcher(prose).find();
        boolean hasLinks = LINK_RE.matcher(combined.normalized).find();
        boolean hasPrivateLink = hasLinks && PRIVATE_LINK_RE.matcher(combined.normalized).find();
        int priceCount = countMatches(PRICE_RE, prose);
        boolean sellerHeader = SELLER_HEADER_RE.matcher(prose).find();
        boolean contactSection = CONTACT_SECTION_RE.matcher(prose).find() && (hasLinks || !foreignHandles.isEmpty());

        int catalogueRows = 0;
        for (String line : lines) {
            String normLine = normalizeText(line).normalized;
            if (CATALOG_PRICE_ROW_RE.matcher(normLine).find()) catalogueRows++;
        }
        boolean catalogueOffer = catalogueRows >= 2 && (hasSaleVerb || sellerHeader || contactSection);

        int carrierHits = countDistinctMatches(CARRIER_RE, prose);
        if (carrierHits >= 3 || (carrierHits >= 2 && (hasSaleVerb || sellerHeader || priceCount > 0 || hasDmCta || catalogueOffer || !topics.isEmpty()))) {
            topics.add("carriers");
            int extra = Math.min(10 * (carrierHits - 1), 20);
            if (extra > 0) multiHitTopic = true;
            topicPoints += 20 + extra;
            factors.add("topic_carriers(+" + (20 + extra) + ")");
        }

        topicPoints = Math.min(topicPoints, 60);
        score += topicPoints;
        if (topics.size() >= 2) multiHitTopic = true;

        if (hasSaleVerb) { score += 25; factors.add("sale_verb(+25)"); }
        if (hasSellerAsk) { score += 55; factors.add("seller_ask(+55)"); }

        boolean commercialCore = topicPoints > 0 || hasSaleVerb || hasSellerAsk || adPromoPhrase || explicitAd;
        if (hasEmojiRouting) { score += 30; factors.add("emoji_contact_routing(+30)"); }
        else if (hasTextRouting) { score += 25; factors.add("text_seller_routing(+25)"); }
        if (hasDmCta && commercialCore) { score += 20; factors.add("dm_call_to_action(+20)"); }

        if (hasLinks) {
            int lp = hasPrivateLink ? 25 : 10;
            score += lp;
            factors.add("promo_links(+" + lp + ")");
        }

        boolean hasPromo = PROMO_RE.matcher(prose).find();
        if (hasInlineUrlButtons) {
            boolean suspiciousButtons = commercialCore || hasLinks || hasPromo || hasDmCta || isForwardedFromChannel || score >= 15;
            int bp = suspiciousButtons ? 35 : 20;
            score += bp;
            factors.add("inline_url_buttons(+" + bp + ")");
        }
        if (isForwardedFromChannel && (commercialCore || hasLinks || hasInlineUrlButtons || hasPromo || hasDmCta)) {
            score += 25;
            factors.add("ad_channel_forward(+25)");
        }

        if (hasRepeatedDestinationAndJoin(combined.normalized, prose)) {
            score += 90;
            factors.add("repeated_invite_ad(+90)");
        }
        if (!foreignHandles.isEmpty() && commercialCore && !hasTextRouting && !hasEmojiRouting) {
            score += 15;
            factors.add("foreign_handle(+15)");
        }
        if (foreignHandles.size() >= 2) {
            score += 15;
            factors.add("multiple_usernames_" + foreignHandles.size() + "(+15)");
        }
        if (catalogueOffer) { score += 60; factors.add("seller_price_catalogue(+60)"); }
        if (contactSection && (commercialCore || catalogueOffer || sellerHeader)) {
            score += 25;
            factors.add("seller_contact_section(+25)");
        }
        if (priceCount >= 2) { score += 20; factors.add("price_list_" + priceCount + "(+20)"); }
        else if (priceCount > 0 && (topicPoints > 0 || hasSaleVerb)) { score += 10; factors.add("price(+10)"); }

        int flagCount = countCountryFlags(text);
        if (flagCount >= 3) { score += 35; factors.add("country_flags_" + flagCount + "(+35)"); }

        int banner = 0;
        int bullets = 0;
        for (String line : lines) {
            if (BANNER_RE.matcher(line).find()) banner++;
            if (CATALOG_BULLET_RE.matcher(line).find()) bullets++;
        }
        if (banner > 0) {
            int bp = banner == 1 ? 15 : 25;
            score += bp;
            factors.add("banner_emojis(+" + bp + ")");
        }
        if (bullets > 0) {
            int blp = bullets == 1 ? 10 : 25;
            score += blp;
            factors.add("bullet_points(+" + blp + ")");
        }
        if (lines.size() >= 4) { score += 15; factors.add("multiblock_layout(+15)"); }
        if (hasSaleVerb && ASSET_SPEC_RE.matcher(prose).find()) {
            score += 15;
            factors.add("asset_specifiers(+15)");
        }

        boolean commercial = commercialCore || hasEmojiRouting || hasTextRouting;
        boolean combo = (topicPoints > 0 && (hasSaleVerb || hasSellerAsk || hasEmojiRouting || hasTextRouting
                || hasDmCta || hasLinks || priceCount > 0 || hasPromo || !foreignHandles.isEmpty()
                || !combined.flags.isEmpty() || (strongTopic && multiHitTopic) || topics.size() >= 2
                || hasInlineUrlButtons || isForwardedFromChannel))
                || (hasInlineUrlButtons && (hasLinks || hasPromo || hasDmCta || isForwardedFromChannel));
        if (combo) {
            score += 20;
            factors.add("commercial_combo(+20)");
        } else if (hasSaleVerb && (hasDmCta || hasLinks || !foreignHandles.isEmpty())) {
            score += 15;
            factors.add("offer_with_contact(+15)");
        }

        int damp = 0;
        if (combined.normalized.contains("?") && !hasEmojiRouting && !hasTextRouting) {
            damp += 25;
            factors.add("question_mark(-25)");
        }
        if (combined.normalized.length() < 70 && lines.size() <= 1) {
            damp += 25;
            factors.add("short_single_line(-25)");
        }
        boolean structuredLayout = lines.size() >= 3 && (hasTextRouting || hasEmojiRouting || bullets > 0 || contactSection || priceCount >= 2);
        if (CONVO_RE.matcher(prose).find() && !structuredLayout && !hasEmojiRouting && bullets == 0) {
            damp += 15;
            factors.add("conversational_markers(-15)");
        }
        int cap = (explicitAd || hasSellerAsk || combo || !combined.flags.isEmpty() || catalogueOffer) ? 0
                : (strongTopic ? 10 : (commercial && score >= 70 ? 20 : 40));
        score = Math.max(0, score - Math.min(damp, cap));

        boolean isLead = false;
        if (isLeadCheckAllowed && meta.isEmpty() && !catalogueOffer && !explicitAd && !hasInlineUrlButtons) {
            boolean strongCommercial = hasSellerAsk || hasTextRouting || hasEmojiRouting || hasLinks
                    || (!foreignHandles.isEmpty() && (topicPoints > 0 || hasSaleVerb || hasDmCta));
            if (!strongCommercial && isGenuineBuyerQuestion(text, ownUsernames)) {
                score = 0;
                isLead = true;
                factors.add("genuine_buyer_question(veto)");
            }
        }

        boolean autoRead = score >= SPAM_THRESHOLD;
        if (explicitAd) {
            autoRead = true;
        } else if (!isLead && !isPing) {
            boolean strongContact = hasEmojiRouting || hasTextRouting || hasDmCta || contactSection
                    || !foreignHandles.isEmpty() || hasLinks || hasInlineUrlButtons;
            boolean saleOrCombo = hasSaleVerb || hasSellerAsk || combo || adPromoPhrase;
            autoRead = hasRepeatedDestinationAndJoin(combined.normalized, prose)
                    || (score >= 80 && (catalogueOffer || (strongContact && saleOrCombo)));
        } else if (!isLead && PING_NAME.equals(pingKind) && score >= SPAM_THRESHOLD - 15) {
            autoRead = true;
        } else if (!isLead && isPing && visualMedia) {
            String rest = NON_WORD_STRIP_RE.matcher(HANDLE_STRIP_RE.matcher(text).replaceAll(" ")).replaceAll("");
            autoRead = rest.length() < 12 || score >= SPAM_THRESHOLD - 15;
        } else if (!isLead && isPing && score >= SPAM_THRESHOLD - 15) {
            autoRead = true;
        }

        String trigger = factors.isEmpty() ? "low_score" : factors.get(0);
        return new SpamReport(score, score >= SPAM_THRESHOLD, autoRead, false, explicitAd, isLead,
                trigger, factors, combined.flags, combined.normalized);
    }

    /** Score one inbound message and return VERDICT_OK / VERDICT_MUTE / VERDICT_DELETE. */
    public static int judge(long dialogId, long senderId, String text,
                            boolean isContact, boolean isGroup) {
        if (!ColgramConfig.isSpamGuardEnabled()) return VERDICT_OK;
        if (dialogId == 0 || senderId == 0 || isContact) return VERDICT_OK;
        if (ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(dialogId)
                || ColgramAutoReplyPolicy.isProtectedSystemOrSpecialDialog(senderId)) {
            return VERDICT_OK;
        }
        if (ColgramColemailerEngine.getInstance().isWhitelisted(senderId)) return VERDICT_OK;
        if (text == null) text = "";

        long now = System.currentTimeMillis();
        if (senders.size() > 512) {
            senders.entrySet().removeIf(e -> now - e.getValue().windowStart > WINDOW_MS);
            if (senders.size() > 512) senders.clear();
        }
        SenderState state = senders.compute(senderId, (k, existing) -> {
            if (existing == null || now - existing.windowStart > WINDOW_MS) {
                SenderState fresh = new SenderState();
                fresh.windowStart = now;
                fresh.messages = 1;
                return fresh;
            }
            existing.messages++;
            return existing;
        });

        SpamReport report = diagnose(text, "", null, !isGroup, false, false, false, false, Collections.emptyList());
        int effectiveScore = report.score + (state.messages >= 3 && report.score >= 25 ? 20 : 0);
        state.score = Math.max(state.score, effectiveScore);

        if (isGroup && (report.isHardSpam || report.isExplicitAd || state.score >= 85)) return VERDICT_DELETE;
        if (state.score >= SPAM_THRESHOLD) return VERDICT_MUTE;
        return VERDICT_OK;
    }

    public static void reset(long senderId) {
        senders.remove(senderId);
    }

    private static boolean hasRepeatedDestinationAndJoin(String clean, String prose) {
        if (!JOIN_PROMO_RE.matcher(prose).find()) return false;
        Matcher m = DEST_RE.matcher(clean);
        Set<String> seen = new HashSet<>();
        int total = 0;
        while (m.find()) {
            total++;
            seen.add(m.group(1));
        }
        return total >= 2 && seen.size() < total;
    }

    private static int countCountryFlags(String text) {
        int count = 0;
        for (String flag : COUNTRY_FLAGS) {
            if (text.contains(flag)) count++;
        }
        return count;
    }

    private static int countDistinctMatches(Pattern pat, String text) {
        Set<String> seen = new HashSet<>();
        Matcher m = pat.matcher(text);
        while (m.find()) seen.add(m.group(0));
        return seen.size();
    }

    private static int countMatches(Pattern pat, String text) {
        int c = 0;
        Matcher m = pat.matcher(text);
        while (m.find()) c++;
        return c;
    }

    private static String lettersOnly(String raw) {
        if (raw == null) return "";
        return NON_ALNUM_CYR_RE.matcher(
                Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
        ).replaceAll("");
    }
}
