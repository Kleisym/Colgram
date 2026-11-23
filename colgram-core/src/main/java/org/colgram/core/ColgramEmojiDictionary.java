package org.colgram.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rich offline Russian + English emoji keyword dictionary.
 * Powers instant emoji & sticker search even on bot accounts or fresh installs where
 * Telegram's server-side TL_messages_getEmojiKeywords is unavailable (BOT_METHOD_INVALID).
 */
public final class ColgramEmojiDictionary {

    public static final class Entry {
        public final String emoji;
        public final String keyword;

        public Entry(String emoji, String keyword) {
            this.emoji = emoji;
            this.keyword = keyword;
        }
    }

    private static final String[][] RAW = new String[][]{
        // Smiles & joy
        {"😀", "улыбка радость весело смайл смех grin smile happy"},
        {"😃", "улыбка радость веселый смайлик happy smile joy"},
        {"😄", "смех радость улыбка весело laugh happy grin"},
        {"😁", "улыбка зубы довольный радость grin beam happy"},
        {"😆", "смех ржач хаха lol xd laugh funny"},
        {"😅", "неловко пот улыбка смех awkward sweat laugh"},
        {"🤣", "ржач смех до слез ахах ржу rofl lol laugh funny"},
        {"😂", "слезы смеха хаха ржу смешно cry laugh lol joy"},
        {"🙂", "улыбка ок нормально привет slight smile ok hi"},
        {"🙃", "сарказм перевернутый шутка upside down sarcasm"},
        {"😉", "подмигиваю намек флирт wink flirt"},
        {"😊", "мило стесняюсь румянец спасибо благодарю blush cute smile thanks"},
        {"😇", "ангел добрый святой хороший angel innocent good"},
        {"🥰", "люблю влюблен сердечки мило обожаю love hearts adore cute"},
        {"😍", "любовь влюблен красиво восторг вау heart eyes love wow"},
        {"🤩", "звезды восторг круто супер вау star eyes amazed cool"},
        {"😘", "поцелуй чмок люблю целую kiss love mwah"},
        {"😗", "поцелуй чмок kiss"},
        {"😚", "поцелуй стесняюсь нежно kiss blush"},
        {"😋", "вкусно ням еда аппетит yum delicious tasty food"},
        {"😛", "язык шутка дразнюсь tongue playful"},
        {"😜", "язык подмигиваю дурачусь прикол crazy wink tongue"},
        {"🤪", "безумие сумасшедший прикол крейзи goofy crazy"},
        {"🤨", "сомнение бровь подозрительно странно hmm suspicious eyebrow"},
        {"🧐", "монокль умный проверка детектив аналитика monocle inspect smart"},
        {"🤓", "очки ботаник умный гик учеба nerd geek smart study"},
        {"😎", "крутой очки босс стиль круто cool sunglasses boss"},
        {"🥳", "праздник др день рождения вечеринка ура поздравляю party birthday celebrate"},
        {"😏", "ухмылка хитрый намек smirk sly"},
        // Sad / negative / shocked
        {"😒", "скучно недоволен раздражен meh unamused bored"},
        {"😞", "грусть печаль расстроен sad disappointed"},
        {"😔", "грустно задумался печально pensive sad"},
        {"😟", "волнуюсь тревога переживаю worried"},
        {"😕", "непонятно смущен confused"},
        {"🙁", "грустный печаль frown sad"},
        {"😣", "боль терплю сложно тяжело persevere struggle"},
        {"😖", "смущен расстроен confounded"},
        {"😫", "устал сил нет измотан tired exhausted"},
        {"😩", "устал нытье weary tired"},
        {"🥺", "пожалуйста милые глазки прошу жалко pleading please puppy eyes"},
        {"😢", "плачу слеза грустно cry tear sad"},
        {"😭", "рыдаю плачу сильно горе слезы sob cry loud sad"},
        {"😤", "пар из носа злюсь гордый бесит triumph angry"},
        {"😠", "злой сердитый гнев злость angry mad"},
        {"😡", "ярость бесит злой красный rage furious angry"},
        {"🤬", "мат ругаюсь бесит злость swear curse angry"},
        {"🤯", "взрыв мозга шок офигеть mind blown shocked"},
        {"😳", "шок краснею стыдно неловко flushed shocked"},
        {"🥵", "жарко жара горячо hot heat"},
        {"🥶", "холодно мороз замерз cold freezing"},
        {"😱", "ужас страх крик шок ааа scream fear scared shock"},
        {"😨", "страшно испуг бояться fear scared"},
        {"😰", "тревога пот нервы страх anxious sweat"},
        {"😥", "фух облегчение грустно sad relieved"},
        {"😓", "пот тяжело устал sweat tired"},
        {"🤗", "обнимаю обнимашки тепло hug warm"},
        {"🤔", "думаю мысль вопрос хм think hmm wonder"},
        {"🫣", "подглядываю страшно стыдно peek shy"},
        {"🤭", "ой смешок секрет хихи giggle oops"},
        {"🤫", "тсс тихо секрет молчи shush quiet secret"},
        {"🤥", "вранье ложь пиноккио lie liar"},
        {"😶", "молчу без слов speechless silent"},
        {"😐", "покерфейс нейтрально без эмоций neutral pokerface"},
        {"😑", "серьезно пофиг expressionless"},
        {"😬", "кринж неловко зубы grimace cringe awkward"},
        {"🙄", "закатываю глаза ну да опять eye roll whatever"},
        {"😯", "удивлен ого hushed surprised"},
        {"😦", "удивление frowned surprised"},
        {"😮", "ого вау удивлен open mouth wow"},
        {"😲", "шок поражен astonished shocked"},
        {"🥱", "зеваю скучно спать хочу yawn sleepy bored"},
        {"😴", "сплю сон спокойной ночи sleep zzz goodnight"},
        {"🤤", "слюнки хочу вкусно drool"},
        {"😪", "сонный устал sleepy"},
        {"😵", "голова кружится нокаут dizzy"},
        {"🤐", "рот на замке молчу секрет zip secret"},
        {"🥴", "пьяный странно криво woozy drunk"},
        {"🤢", "тошнит фу мерзко nauseated sick"},
        {"🤮", "рвота фу гадость vomit gross"},
        {"🤧", "чихаю болею простуда sneeze sick cold"},
        {"😷", "маска болею врач вирус mask sick doctor"},
        {"🤒", "температура болен градусник fever sick"},
        {"🤕", "травма бинт болит hurt bandage"},
        {"🤑", "деньги богатый доллары баксы money rich cash"},
        {"🤠", "ковбой шляпа cowboy"},
        {"😈", "черт дьявол хитрый зло улыбка devil imp"},
        {"👿", "демон злой черт angry devil"},
        {"🤡", "клоун цирк шут clown"},
        {"💩", "какашка говно дерьмо poop shit"},
        {"👻", "призрак привидение бу хэллоуин ghost halloween"},
        {"💀", "череп смерть умер смех до смерти skull dead rip"},
        {"☠️", "череп кости опасно яд skull danger"},
        {"👽", "пришелец инопланетянин нло alien ufo"},
        {"👾", "игра пиксели ретро монстр game retro alien"},
        {"🤖", "робот ии бот киборг ai bot robot"},
        {"🎃", "тыква хэллоуин pumpkin halloween"},
        // Cats & Animals
        {"😺", "кот котик кошка улыбка cat smile"},
        {"😸", "кот смеется котик cat grin"},
        {"😹", "кот ржет смех кошка cat laugh"},
        {"😻", "кот любовь сердечки котик cat love"},
        {"😼", "кот хитрый котик cat smirk"},
        {"😽", "кот поцелуй котик cat kiss"},
        {"🙀", "кот шок испуг котик cat shock"},
        {"😿", "кот плачет грустный котик cat cry"},
        {"😾", "кот злой котик cat angry"},
        {"🐱", "кот кошка котик котенок мяу cat kitty meow"},
        {"🐈", "кот кошка питомец cat pet"},
        {"🐶", "собака пес щенок гав dog puppy"},
        {"🐕", "собака пес питомец dog"},
        {"🐺", "волк ауф wolf"},
        {"🦊", "лиса лисичка рыжий fox"},
        {"🦝", "енот raccoon"},
        {"🐻", "медведь мишка bear"},
        {"🐼", "панда мило panda"},
        {"🐨", "коала koala"},
        {"🐯", "тигр tiger"},
        {"🦁", "лев король lion"},
        {"🐮", "корова му cow"},
        {"🐷", "свинья хрю поросенок pig"},
        {"🐸", "лягушка пепе ква frog pepe"},
        {"🐵", "обезьяна макака monkey"},
        {"🙈", "обезьяна не вижу стыдно скрыть see no evil"},
        {"🙉", "обезьяна не слышу hear no evil"},
        {"🙊", "обезьяна ой не говорю speak no evil"},
        {"🐔", "курица петух chicken"},
        {"🐧", "пингвин линукс penguin linux"},
        {"🐦", "птица птичка bird"},
        {"🦅", "орел свобода eagle"},
        {"🦉", "сова мудрость ночь owl night"},
        {"🦋", "бабочка красота butterfly"},
        {"🐝", "пчела мед bee honey"},
        {"🐢", "черепаха медленно turtle slow"},
        {"🐍", "змея питон python snake"},
        {"🦖", "динозавр рекс dino"},
        {"🐙", "осьминог octopus"},
        {"🦈", "акула shark"},
        {"🐬", "дельфин dolphin"},
        {"🐳", "кит море whale"},
        {"🦄", "единорог магия unicorn magic"},
        {"🐉", "дракон огонь dragon"},
        // Hearts & Romance
        {"❤️", "сердце любовь люблю лайк красное heart love red like"},
        {"🧡", "оранжевое сердце любовь orange heart"},
        {"💛", "желтое сердце дружба yellow heart"},
        {"💚", "зеленое сердце green heart"},
        {"💙", "синее сердце blue heart"},
        {"💜", "фиолетовое сердце purple heart"},
        {"🖤", "черное сердце готика black heart"},
        {"🤍", "белое сердце чистота white heart"},
        {"🤎", "коричневое сердце brown heart"},
        {"💔", "разбитое сердце боль расставание broken heart"},
        {"❤️‍🔥", "горящее сердце страсть огонь heart passion flame"},
        {"💕", "сердечки любовь пара two hearts love"},
        {"💞", "сердечки кружатся любовь revolving hearts"},
        {"💓", "бьется сердце пульс beating heart"},
        {"💗", "растущее сердце любовь growing heart"},
        {"💖", "блестящее сердце любовь sparkling heart"},
        {"💘", "стрела купидон любовь cupid heart"},
        {"💝", "подарок сердце любовь gift heart"},
        {"💋", "поцелуй губы kiss lips"},
        {"💯", "сто процентов идеально топ факт 100 hundred perfect facts"},
        {"💢", "злость гнев anger"},
        {"💥", "взрыв бум бабах boom explosion"},
        {"💫", "звезда искра головокружение dizzy star"},
        {"💦", "капли вода пот брызги water drops sweat"},
        {"💨", "ветер бегу быстро дым dash fast wind"},
        {"💤", "сон сплю zzz sleep"},
        // Hands & Gestures
        {"👋", "привет пока машу рука wave hi hello bye"},
        {"🤚", "стоп ладонь рука stop hand"},
        {"✋", "стоп пять ладонь high five stop"},
        {"👌", "ок отлично супер хорошо ok perfect nice"},
        {"🤌", "итальянский жест шедевр идеально chefs kiss"},
        {"✌️", "мир победа виктори peace victory v"},
        {"🤞", "удача скрестил пальцы fingers crossed luck"},
        {"🤟", "люблю рок жест love you rock"},
        {"🤘", "рок метал круто rock metal"},
        {"🤙", "позвони шака круто call me shaka"},
        {"👈", "влево указатель left"},
        {"👉", "вправо указатель right"},
        {"👆", "вверх указатель up"},
        {"👇", "вниз указатель down"},
        {"👍", "лайк класс хорошо да ок супер thumbs up like ok good yes"},
        {"👎", "дизлайк плохо нет фу thumbs down dislike bad no"},
        {"✊", "кулак сила борьба fist power"},
        {"👊", "бро кулак удар привет fist bump bro"},
        {"👏", "аплодисменты браво молодец хлопаю clap applause bravo"},
        {"🙌", "ура победа руки вверх hooray celebrate"},
        {"🤝", "рукопожатие сделка договорились дружба handshake deal agree"},
        {"🙏", "пожалуйста спасибо молюсь благодарю pray please thanks"},
        {"💪", "сила мышцы качок мощь спорт strong muscle gym power"},
        {"🧠", "мозг ум интеллект думать ии brain smart ai think"},
        {"👀", "глаза смотрю слежу интересно eyes look watch"},
        // Fire, Weather & Nature
        {"🔥", "огонь пожар топ круто горячо имба fire lit hot flame"},
        {"✨", "искры магия блеск красиво чисто sparkles magic shine"},
        {"⭐", "звезда рейтинг избранное топ star favorite"},
        {"🌟", "сияющая звезда супер glowing star"},
        {"⚡", "молния энергия быстро ток lightning fast energy zap"},
        {"☀️", "солнце лето жара день утро sun summer morning"},
        {"🌙", "луна ночь вечер сон moon night"},
        {"🌈", "радуга цвет красота rainbow"},
        {"☁️", "облако туча погода cloud weather"},
        {"🌧️", "дождь ливень грусть rain"},
        {"❄️", "снег зима мороз снежинка snow winter cold"},
        {"🌊", "волна море океан вода wave sea ocean"},
        {"🌹", "роза цветок любовь романтика rose flower"},
        {"🌸", "сакура цветок весна мило cherry blossom flower"},
        {"🌻", "подсолнух цветок лето sunflower"},
        {"🍀", "клевер удача фортуна clover luck"},
        {"🎄", "елка новый год рождество christmas tree new year"},
        // Food & Drinks
        {"🍕", "пицца еда вкусно pizza food"},
        {"🍔", "бургер фастфуд еда burger food"},
        {"🍟", "картошка фри еда fries"},
        {"🌭", "хотдог еда hotdog"},
        {"🍣", "суши роллы япония еда sushi"},
        {"🍜", "рамен лапша суп еда ramen noodles"},
        {"🍩", "пончик сладкое десерт donut"},
        {"🍪", "печенье куки сладкое cookie"},
        {"🎂", "торт день рождения др праздник cake birthday"},
        {"🍰", "тортик десерт сладкое cake dessert"},
        {"🍫", "шоколад сладкое chocolate"},
        {"🍓", "клубника ягода вкусно strawberry"},
        {"🍉", "арбуз лето watermelon"},
        {"🍌", "банан фрукт banana"},
        {"🍎", "яблоко фрукт apple"},
        {"☕", "кофе утро чай кружка coffee tea morning"},
        {"🍵", "чай зеленый уют tea"},
        {"🥤", "кола напиток стакан газировка cola soda drink"},
        {"🍺", "пиво бар пятница beer"},
        {"🍻", "пиво тост чокнуться друзья cheers beer"},
        {"🥂", "шампанское тост праздник новый год champagne cheers"},
        {"🍷", "вино вечер романтика wine"},
        // Tech, Crypto, Money, Work, Gaming
        {"💻", "ноутбук компьютер код работа программист laptop computer code dev"},
        {"📱", "телефон смартфон мобила звонок phone mobile"},
        {"🖥️", "компьютер монитор пк pc desktop"},
        {"⌨️", "клавиатура код печать keyboard"},
        {"🎮", "игры геймпад геймер играть game gaming controller"},
        {"🕹️", "джойстик ретро игры joystick game"},
        {"🎧", "наушники музыка трек слушать headphones music"},
        {"🎵", "музыка нота песня трек music note song"},
        {"🎶", "музыка ноты песни music notes"},
        {"🎤", "микрофон петь голос подкаст mic voice sing"},
        {"🎬", "кино фильм видео съемка movie film video"},
        {"📷", "фото камера снимок camera photo"},
        {"💰", "деньги мешок богатство кэш бабки money bag cash rich"},
        {"💵", "доллар деньги купюра бакс dollar money cash"},
        {"💸", "траты деньги улетели кэш money wings spend"},
        {"💳", "карта оплата банк платеж card pay bank"},
        {"💎", "алмаз бриллиант премиум тон крипта diamond gem premium ton"},
        {"📈", "рост график вверх крипта акции профит chart up stonks profit"},
        {"📉", "падение график вниз убыток chart down"},
        {"🚀", "ракета космос запуск взлет туземун rocket launch moon"},
        {"✈️", "самолет полет путешествие отпуск plane travel flight"},
        {"🚗", "машина авто тачка дорога car auto"},
        {"🏠", "дом квартира уют home house"},
        {"🎉", "хлопушка праздник ура вечеринка поздравляю party tada celebrate"},
        {"🎊", "конфетти праздник шарик confetti party"},
        {"🎁", "подарок сюрприз праздник др gift present"},
        {"🏆", "кубок победа чемпион награда топ trophy win champion"},
        {"🥇", "первое место золото медаль победа gold medal first"},
        {"👑", "корона король босс топ vip crown king boss"},
        {"🔑", "ключ пароль доступ секрет key password"},
        {"🔒", "замок приватность защита безопасность lock privacy security"},
        {"🔓", "открыто разблокировано unlock"},
        {"🛡️", "щит защита безопасность антивирус shield protect security"},
        {"📧", "почта письмо емейл email mail letter"},
        {"✉️", "письмо конверт сообщение почта mail envelope message"},
        {"📌", "закрепить пин заметка важно pin note"},
        {"📝", "заметка текст написать список memo write note"},
        {"📚", "книги учеба знания чтение books study learn"},
        {"🔍", "поиск лупа найти search find"},
        {"⚙️", "настройки шестеренка конфиг settings gear config"},
        {"🛠️", "инструменты ремонт фикс разработка tools fix dev"},
        {"🧪", "тест эксперимент наука лаборатория test lab science"},
        {"💡", "идея лампочка эврика мысль совет idea bulb tip"},
        {"⏰", "будильник время таймер пора alarm clock time"},
        {"📅", "календарь дата встреча план calendar date"},
        // Symbols & Checks
        {"✅", "галочка да готово выполнено ок check done yes ok"},
        {"❌", "крестик нет ошибка отмена cross no error cancel"},
        {"⚠️", "внимание предупреждение опасно warning alert danger"},
        {"❗", "важно восклицание внимание important exclamation"},
        {"❓", "вопрос что непонятно question what"},
        {"➕", "плюс добавить plus add"},
        {"➖", "минус убрать minus"},
        {"🔔", "уведомление колокольчик звонок bell notification"},
        {"🔕", "без звука тишина mute silent"},
        {"🔴", "красный круг стоп запись red circle live"},
        {"🟢", "зеленый круг онлайн ок green circle online"},
        {"🟡", "желтый круг ожидание yellow circle"},
        {"🔵", "синий круг blue circle"},
        {"🟣", "фиолетовый круг purple circle"},
        {"⚫", "черный круг black circle"},
        {"⚪", "белый круг white circle"},
        // Flags
        {"🇷🇺", "россия флаг рф москва russia ru flag"},
        {"🇺🇸", "сша америка флаг usa us flag"},
        {"🇺🇦", "украина флаг ukraine ua flag"},
        {"🇧🇾", "беларусь флаг belarus by flag"},
        {"🇰🇿", "казахстан флаг kazakhstan kz flag"},
        {"🇯🇵", "япония флаг аниме токио japan jp flag"},
        {"🇩🇪", "германия флаг germany de flag"},
        {"🇬🇧", "британия англия флаг uk gb flag"},
        {"🇫🇷", "франция флаг париж france fr flag"},
        {"🇨🇳", "китай флаг china cn flag"},
        {"🇰🇷", "корея флаг korea kr flag"},
        {"🇹🇷", "турция флаг turkey tr flag"}
    };

    private ColgramEmojiDictionary() {}

    /**
     * Searches emojis by Russian or English query (prefix matches ranked ahead of substring matches).
     */
    public static List<Entry> search(String rawQuery, int limit) {
        List<Entry> out = new ArrayList<>();
        if (rawQuery == null || limit <= 0) {
            return out;
        }
        String q = rawQuery.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (q.isEmpty()) {
            // Return top popular defaults if empty query requested
            int cap = Math.min(limit, 24);
            for (int i = 0; i < cap && i < RAW.length; i++) {
                out.add(new Entry(RAW[i][0], firstToken(RAW[i][1])));
            }
            return out;
        }

        // Also compute simple Latin<->Russian keyboard/translit variants so "kot", "pri", "lyubov" match Russian keywords too!
        String translitRu = latinToCyrillicApprox(q);

        Map<String, String> exact = new LinkedHashMap<>();
        Map<String, String> prefix = new LinkedHashMap<>();
        Map<String, String> contains = new LinkedHashMap<>();

        for (String[] row : RAW) {
            String emoji = row[0];
            String tags = row[1].toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (emoji.equals(q) || emoji.replace("\uFE0F", "").equals(q.replace("\uFE0F", ""))) {
                exact.put(emoji, firstToken(tags));
                continue;
            }
            String[] words = tags.split(" ");
            String matchedExact = null;
            String matchedPrefix = null;
            String matchedContains = null;
            for (String w : words) {
                if (w.isEmpty()) continue;
                if (w.equals(q) || (!translitRu.isEmpty() && w.equals(translitRu))) {
                    matchedExact = w;
                    break;
                } else if (w.startsWith(q) || (!translitRu.isEmpty() && w.startsWith(translitRu))) {
                    if (matchedPrefix == null) matchedPrefix = w;
                } else if (q.length() >= 2 && (w.contains(q) || (!translitRu.isEmpty() && translitRu.length() >= 2 && w.contains(translitRu)))) {
                    if (matchedContains == null) matchedContains = w;
                }
            }
            if (matchedExact != null) {
                exact.putIfAbsent(emoji, matchedExact);
            } else if (matchedPrefix != null) {
                prefix.putIfAbsent(emoji, matchedPrefix);
            } else if (matchedContains != null) {
                contains.putIfAbsent(emoji, matchedContains);
            }
        }

        appendUpTo(out, exact, limit);
        appendUpTo(out, prefix, limit);
        appendUpTo(out, contains, limit);
        return out;
    }

    private static void appendUpTo(List<Entry> out, Map<String, String> src, int limit) {
        for (Map.Entry<String, String> e : src.entrySet()) {
            if (out.size() >= limit) return;
            boolean dup = false;
            for (Entry existing : out) {
                if (existing.emoji.equals(e.getKey())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(new Entry(e.getKey(), e.getValue()));
            }
        }
    }

    private static String firstToken(String tags) {
        int idx = tags.indexOf(' ');
        return idx > 0 ? tags.substring(0, idx) : tags;
    }

    private static String latinToCyrillicApprox(String s) {
        boolean hasAscii = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                hasAscii = true;
                break;
            }
        }
        if (!hasAscii) return "";
        String r = s.replace("shch", "щ").replace("sch", "щ").replace("zh", "ж")
                .replace("ch", "ч").replace("sh", "ш").replace("yu", "ю")
                .replace("ya", "я").replace("yo", "е").replace("kh", "х");
        StringBuilder sb = new StringBuilder(r.length());
        for (int i = 0; i < r.length(); i++) {
            switch (r.charAt(i)) {
                case 'a': sb.append('а'); break;
                case 'b': sb.append('б'); break;
                case 'v': sb.append('в'); break;
                case 'g': sb.append('г'); break;
                case 'd': sb.append('д'); break;
                case 'e': sb.append('е'); break;
                case 'z': sb.append('з'); break;
                case 'i': sb.append('и'); break;
                case 'y': sb.append('й'); break;
                case 'k': sb.append('к'); break;
                case 'l': sb.append('л'); break;
                case 'm': sb.append('м'); break;
                case 'n': sb.append('н'); break;
                case 'o': sb.append('о'); break;
                case 'p': sb.append('п'); break;
                case 'r': sb.append('р'); break;
                case 's': sb.append('с'); break;
                case 't': sb.append('т'); break;
                case 'u': sb.append('у'); break;
                case 'f': sb.append('ф'); break;
                case 'h': sb.append('х'); break;
                case 'c': sb.append('ц'); break;
                default: sb.append(r.charAt(i)); break;
            }
        }
        return sb.toString();
    }
}
