package com.petr.bot;

import com.petr.bot.filters.isAdmin;
import com.petr.configmanager.ConfigManager;
import com.petr.configmanager.ConfigManagerImpl;
import com.petr.configmanager.Decision;
import com.petr.exception.ConfigNameTakenException;
import io.github.natanimn.telebof.BotClient;
import io.github.natanimn.telebof.BotContext;
import io.github.natanimn.telebof.annotations.CallbackHandler;
import io.github.natanimn.telebof.annotations.MessageHandler;
import io.github.natanimn.telebof.enums.MessageType;
import io.github.natanimn.telebof.enums.ParseMode;
import io.github.natanimn.telebof.exceptions.BadRequest;
import io.github.natanimn.telebof.requests.edit.EditMessageText;
import io.github.natanimn.telebof.requests.send.SendMessage;
import io.github.natanimn.telebof.types.bot.BotCommand;
import io.github.natanimn.telebof.types.keyboard.InlineKeyboardButton;
import io.github.natanimn.telebof.types.keyboard.InlineKeyboardMarkup;
import io.github.natanimn.telebof.types.updates.CallbackQuery;
import io.github.natanimn.telebof.types.updates.Message;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Bot {
    /** По умолчанию у клиента 2 потока: пара зависших запросов к панели останавливала весь бот. */
    private static final int HANDLER_THREADS = 8;

    final private BotClient bot;
    final private ConfigManager configManager = new ConfigManagerImpl();

    final private BotCommand[] commands = {
            new BotCommand("start", "Запустить бота"),
            new BotCommand("help", "Помощь"),
            new BotCommand("get_config", "Получить конфиг"),
            new BotCommand("cancel", "Отменить ввод имени конфига"),
            new BotCommand("how_to_use", "Показать инструкцию по использованию конфига")
    };

    public Bot() {
        this(newClient());
    }

    /** Отдельный конструктор — чтобы сценарные тесты могли подставить клиента с локальным Bot API. */
    Bot(BotClient client) {
        bot = client;
        bot.context.setMyCommands(commands).exec();
        bot.addHandler(new BotHandler());
    }

    private static BotClient newClient() {
        String BOT_TOKEN = System.getenv("BOT_TOKEN");
        if (BOT_TOKEN == null) throw new RuntimeException("BOT_TOKEN не задан");
        return buildClient(new BotClient.Builder(BOT_TOKEN));
    }

    static BotClient buildClient(BotClient.Builder builder) {
        BotClient client = builder.numThreads(HANDLER_THREADS).build();
        TelebofThreadSafety.install(client);
        return client;
    }

    public void startBot() {
        bot.startPolling();
    }

    class BotHandler {

        private static final String STATE_AWAITING_CONFIG_NAME = "awaiting_config_name";
        private static final String STATE_ADMIN = "admin_state";
        private static final String STATE_ADMIN_DELETE = "admin_delete_state";
        private static final String CONFIG_NAME_SUFFIX = "_config";
        private static final Pattern CONFIG_BASE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

        private final Map<Long, Integer> awaitingMessageId = new ConcurrentHashMap<>();
        private final Map<Long, Integer> deleteMessageId = new ConcurrentHashMap<>();
        private final Map<Long, String> pendingConfigType = new ConcurrentHashMap<>();
        private final Map<Long, String> pendingCountry = new ConcurrentHashMap<>();
        /**
         * Уведомления админам по открытым заявкам: "userId:country" → adminId → messageId.
         * Хранятся в памяти: после рестарта чужое сообщение обновится, когда админ нажмёт в нём кнопку.
         */
        private final Map<String, Map<Long, Integer>> adminNotifications = new ConcurrentHashMap<>();

        private static final String WELCOME_TEXT = """
                Привет!

                Этот бот управляет твоими VPN-конфигами.
                Нажми кнопку ниже, чтобы получить свой конфиг.""";

        private static final String ADMIN_HOME_TEXT = "Панель администратора. Выберите действие:";

        private static final String NAME_RULES_TEXT = """
                Введи имя одним словом, только латиница (a-z, 0-9, _).
                Пример: petr

                Суффикс _config добавлю автоматически.""";

        private static final String CREATE_ERROR_TEXT = """
                Ошибка при создании конфига: сервер сейчас не отвечает.

                Попробуйте ещё раз позже или обратитесь к администратору.""";

        private static final String GERMANY_WARNING_TEXT = """
                ⛔ ВНИМАНИЕ — Немецкий сервер ⛔

                Использование торрентов при активном VPN-соединении строго ЗАПРЕЩЕНО.

                Немецкое законодательство предусматривает строгие штрафы за нарушение авторских прав, в том числе через VPN. Мы обязаны соблюдать эти требования.

                Нарушение данного правила ведёт к немедленной блокировке вашего конфига без предупреждения и без возможности восстановления.

                Вы принимаете это условие и берёте на себя полную ответственность?""";

        private static InlineKeyboardMarkup userMainKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {new InlineKeyboardButton("Получить конфиг", "user:get_config")}
            });
        }

        private static InlineKeyboardMarkup cancelKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {new InlineKeyboardButton("Отмена", "user:cancel")}
            });
        }

        private static InlineKeyboardMarkup checkStatusKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {new InlineKeyboardButton("Проверить статус", "user:check_status")}
            });
        }

        private static InlineKeyboardMarkup countryKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {
                            new InlineKeyboardButton("🇱🇻 Латвия", "country:latv"),
                            new InlineKeyboardButton("🇩🇪 Германия", "country:germ")
                    },
                    {new InlineKeyboardButton("Отмена", "user:cancel")}
            });
        }

        private static InlineKeyboardMarkup germanyWarningKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {new InlineKeyboardButton("✅ Принимаю условия", "germ:confirm")},
                    {new InlineKeyboardButton("❌ Отмена", "user:cancel")}
            });
        }

        /** Страна лежит в самих кнопках, чтобы выбор пережил рестарт бота. */
        private static InlineKeyboardMarkup configTypeKeyboard(String country) {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {
                            new InlineKeyboardButton("WebSocket", "config_type:ws:" + country),
                            new InlineKeyboardButton("xHTTP", "config_type:xhttp:" + country),
                            new InlineKeyboardButton("Оба", "config_type:both:" + country)
                    },
                    {new InlineKeyboardButton("Отмена", "user:cancel")}
            });
        }

        private static InlineKeyboardMarkup adminKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {new InlineKeyboardButton("Синхронизировать с панелью", "admin:sync")},
                    {new InlineKeyboardButton("Список пользователей", "admin:users")},
                    {new InlineKeyboardButton("Удалить конфиг", "admin:delete")},
                    {new InlineKeyboardButton("Выйти из режима", "admin:exit")}
            });
        }

        private static InlineKeyboardMarkup cancelAdminKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {new InlineKeyboardButton("Отмена", "admin:cancel_delete")}
            });
        }

        private static InlineKeyboardMarkup approvalKeyboard(long userChatId, String country) {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {
                            new InlineKeyboardButton("Одобрить", "approve:" + userChatId + ":" + country),
                            new InlineKeyboardButton("Отклонить", "reject:" + userChatId + ":" + country)
                    }
            });
        }

        @MessageHandler(commands = "start")
        void onStart(BotContext bot, Message message) {
            bot.clearState(message.chat.id);
            clearPending(message.chat.id);
            configManager.onStart(message.chat.id, message.chat.username);
            bot.sendMessage(message.chat.id, WELCOME_TEXT)
                    .replyMarkup(userMainKeyboard()).exec();
        }

        @MessageHandler(commands = "how_to_use")
        void onHowToUse(BotContext bot, Message message) throws IOException {
            InputStream is = getClass().getResourceAsStream("/bot-resources/img/how-to-use-1.jpg");
            if (is == null) throw new RuntimeException("Изображение не найдено в ресурсах!");

            File tempFile = File.createTempFile("how-to-use-1", ".jpg");
            tempFile.deleteOnExit();

            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                is.transferTo(fos);
            }

            bot.sendPhoto(message.chat.id, tempFile).caption("""
            1) Скачайте из Play market/App store приложение Happ
            2) Копируете любую ссылку начинающуюся на vless:// из сообщения отправленного ботом
            3) В приложении Happ в левом нижнем углу нажимаем кнопку "буфер обмена" и ссылка должна добавиться
            4) Если всё пошло хорошо — нажимаем кнопку включения, и всё работает!
            """).exec();
        }

        @MessageHandler(commands = "help")
        void onHelp(BotContext bot, Message message) {
            bot.sendMessage(message.chat.id, """
                    Команды бота:
                    /start — зарегистрироваться и открыть главное меню
                    /get_config — получить конфиг
                    /cancel — отменить ввод имени конфига
                    /how_to_use - как использовать конфиг
                    """).exec();
        }

        @MessageHandler(commands = "get_config")
        void onGetConfig(BotContext bot, Message message) {
            bot.clearState(message.chat.id);
            if (!configManager.isRegistered(message.chat.id)) {
                bot.sendMessage(message.chat.id,
                        "Вы не зарегистрированы. Отправьте /start, чтобы бот вас запомнил.").exec();
                return;
            }

            handleGetConfig(bot, message.chat.id, null);
        }

        @MessageHandler(commands = "cancel", state = STATE_AWAITING_CONFIG_NAME, priority = 10)
        void onCancelConfigName(BotContext bot, Message message) {
            bot.clearState(message.chat.id);
            clearPending(message.chat.id);
            bot.sendMessage(message.chat.id, WELCOME_TEXT).replyMarkup(userMainKeyboard()).exec();
        }

        // Приоритет 0 у обработчика без состояния перехватывал /cancel раньше режима ввода имени
        @MessageHandler(commands = "cancel", priority = 20)
        void onCancelAny(BotContext bot, Message message) {
            bot.sendMessage(message.chat.id, "Нечего отменять.").exec();
        }

        @MessageHandler(type = MessageType.TEXT, state = STATE_AWAITING_CONFIG_NAME, priority = 200)
        void onAwaitingConfigName(BotContext bot, Message message) throws IOException, InterruptedException {
            String text = message.text != null ? message.text.trim() : "";
            if (text.startsWith("/")) {
                bot.sendMessage(message.chat.id,
                        "Сейчас ожидается имя конфига или команда /cancel.").exec();
                return;
            }

            String base = stripConfigSuffix(text);
            if (!CONFIG_BASE_NAME_PATTERN.matcher(base).matches()) {
                bot.sendMessage(message.chat.id,
                        "Неверный формат. Только латиница, цифры и _ (например: petr). Попробуй ещё раз.").exec();
                return;
            }

            long chatId = message.chat.id;
            String configType = pendingConfigType.getOrDefault(chatId, "ws");
            String country = pendingCountry.getOrDefault(chatId, "latv");
            Integer editMsgId = awaitingMessageId.get(chatId);

            try {
                bot.deleteMessage(chatId, message.message_id).exec();
            } catch (Exception ignored) {
            }

            String configName = base + CONFIG_NAME_SUFFIX;
            try {
                String[] configs = configManager.getConfigs(chatId, configName, configType, country);
                bot.clearState(chatId);
                clearPending(chatId);
                sendConfigResult(bot, chatId, message.chat.username, country, configs, configName, editMsgId);
            } catch (ConfigNameTakenException e) {
                // Режим ввода остаётся: пользователь вводит другое имя
                show(bot, chatId, editMsgId, nameTakenText(configName), cancelKeyboard(), false);
            } catch (Exception e) {
                bot.clearState(chatId);
                clearPending(chatId);
                showCreateError(bot, chatId, editMsgId, e);
            }
        }

        @CallbackHandler(data = "user:get_config")
        void onCallbackGetConfig(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            int msgId = query.message.message_id;

            if (!configManager.isRegistered(chatId)) {
                show(bot, chatId, msgId, "Вы не зарегистрированы. Отправьте /start.", null, false);
                return;
            }

            handleGetConfig(bot, chatId, msgId);
        }

        @CallbackHandler(data = "country:latv")
        void onCallbackCountryLatv(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            // На Риге нет XHTTP — выбирать тип не из чего: WS (+ Reality добавится автоматически)
            requestConfig(bot, query, "ws", "latv");
        }

        @CallbackHandler(data = "country:germ")
        void onCallbackCountryGerm(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            show(bot, chatId, msgId, GERMANY_WARNING_TEXT, germanyWarningKeyboard(), false);
        }

        @CallbackHandler(data = "germ:confirm")
        void onCallbackGermConfirm(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            show(bot, chatId, msgId, "Выбери тип конфига:", configTypeKeyboard("germ"), false);
        }

        @CallbackHandler(regex = "config_type:.*")
        void onCallbackConfigType(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            String[] parts = query.data.split(":");
            if (parts.length < 3) {
                // Кнопка старого формата без страны (нажата после обновления бота): уточняем страну
                handleGetConfig(bot, query.from.id, query.message.message_id);
                return;
            }
            requestConfig(bot, query, parts[1], "germ".equals(parts[2]) ? "germ" : "latv");
        }

        /** Создаёт/дополняет конфиг; если нет подходящего @username — просит ввести имя. */
        private void requestConfig(BotContext bot, CallbackQuery query, String configType, String country) {
            long chatId = query.from.id;
            int msgId = query.message.message_id;

            if (!configManager.isRegistered(chatId)) {
                show(bot, chatId, msgId, "Вы не зарегистрированы. Отправьте /start.", null, false);
                return;
            }

            String configName = resolveConfigName(query.from.username, chatId);
            if (configName == null) {
                askForConfigName(bot, chatId, msgId, configType, country,
                        "У тебя не задан @username или он не подходит для имени конфига.\n\n" + NAME_RULES_TEXT);
                return;
            }

            try {
                String[] configs = configManager.getConfigs(chatId, configName, configType, country);
                clearPending(chatId);
                sendConfigResult(bot, chatId, query.from.username, country, configs, configName, msgId);
            } catch (ConfigNameTakenException e) {
                askForConfigName(bot, chatId, msgId, configType, country, nameTakenText(configName));
            } catch (Exception e) {
                clearPending(chatId);
                showCreateError(bot, chatId, msgId, e);
            }
        }

        private String resolveConfigName(String username, long chatId) {
            if (username != null && !username.isBlank()) {
                String base = stripConfigSuffix(username);
                if (CONFIG_BASE_NAME_PATTERN.matcher(base).matches()) {
                    return base + CONFIG_NAME_SUFFIX;
                }
            }
            return configManager.getExistingConfigName(chatId);
        }

        private void askForConfigName(BotContext bot, long chatId, int msgId, String configType, String country, String text) {
            pendingConfigType.put(chatId, configType);
            pendingCountry.put(chatId, country);
            awaitingMessageId.put(chatId, msgId);
            bot.setState(chatId, STATE_AWAITING_CONFIG_NAME);
            show(bot, chatId, msgId, text, cancelKeyboard(), false);
        }

        private static String nameTakenText(String configName) {
            return "Имя " + stripConfigSuffix(configName) + " уже занято.\n\n" + NAME_RULES_TEXT;
        }

        private void showCreateError(BotContext bot, long chatId, Integer msgId, Exception e) {
            System.out.println("[Bot] ошибка создания конфига для " + chatId + ": " + e);
            show(bot, chatId, msgId, CREATE_ERROR_TEXT, userMainKeyboard(), false);
        }

        private void clearPending(long chatId) {
            awaitingMessageId.remove(chatId);
            pendingConfigType.remove(chatId);
            pendingCountry.remove(chatId);
        }

        @CallbackHandler(data = "user:cancel")
        void onCallbackCancel(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            bot.clearState(chatId);
            clearPending(chatId);
            show(bot, chatId, query.message.message_id, WELCOME_TEXT, userMainKeyboard(), false);
        }

        @CallbackHandler(data = "user:check_status")
        void onCallbackCheckStatus(BotContext bot, CallbackQuery query) throws IOException, InterruptedException {
            answer(bot, query, null);
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            Map<String, String[]> configs = configManager.getConfigs(chatId);
            List<String> waiting = configManager.getWaitingCountries(chatId);
            List<String> rejected = configManager.getRejectedCountries(chatId);

            StringBuilder text = new StringBuilder();
            if (!configs.isEmpty()) {
                text.append(formatAllConfigLinks(configs));
            }
            if (!waiting.isEmpty()) {
                appendParagraph(text, "Конфиг (" + countryTitles(waiting) + ") ожидает одобрения администратора.\nОжидайте...");
            }
            if (!rejected.isEmpty()) {
                appendParagraph(text, "Заявка (" + countryTitles(rejected) + ") отклонена администратором.");
            }
            if (text.isEmpty()) {
                text.append("У вас нет конфигов и открытых заявок.");
            }

            InlineKeyboardMarkup keyboard = !waiting.isEmpty() ? checkStatusKeyboard()
                    : configs.isEmpty() ? userMainKeyboard() : null;
            show(bot, chatId, msgId, text.toString(), keyboard, true);
        }

        private static void appendParagraph(StringBuilder text, String paragraph) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(paragraph);
        }

        private static String countryTitles(List<String> countries) {
            return countries.stream().map(BotHandler::countryTitle).collect(Collectors.joining(", "));
        }

        @MessageHandler(filter = isAdmin.class, commands = "admin")
        void onAdmin(BotContext bot, Message message) {
            bot.setState(message.chat.id, STATE_ADMIN);
            bot.sendMessage(message.chat.id, ADMIN_HOME_TEXT)
                    .replyMarkup(adminKeyboard()).exec();
        }

        @MessageHandler(filter = isAdmin.class, commands = "syncPanel")
        void onSyncPanel(BotContext bot, Message message) throws IOException, InterruptedException {
            bot.sendMessage(message.chat.id, truncate(buildSyncResult())).exec();
        }

        @MessageHandler(filter = isAdmin.class, commands = "users")
        void onListUsers(BotContext bot, Message message) {
            bot.sendMessage(message.chat.id, truncate(configManager.listUsers())).exec();
        }

        @MessageHandler(filter = isAdmin.class, state = STATE_ADMIN, commands = "exAdmin")
        void onExitAdmin(BotContext bot, Message message) {
            bot.clearState(message.chat.id);
            bot.sendMessage(message.chat.id, "Вы вышли из админ режима.").exec();
        }

        @CallbackHandler(data = "admin:sync", filter = isAdmin.class)
        void onCallbackSync(BotContext bot, CallbackQuery query) throws IOException, InterruptedException {
            answer(bot, query, "Синхронизирую...");
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            String result = buildSyncResult();
            show(bot, chatId, msgId, truncate(ADMIN_HOME_TEXT + "\n\n" + result), adminKeyboard(), false);
        }

        @CallbackHandler(data = "admin:users", filter = isAdmin.class)
        void onCallbackUsers(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            String users = configManager.listUsers();
            show(bot, chatId, msgId, truncate(ADMIN_HOME_TEXT + "\n\n" + users), adminKeyboard(), false);
        }

        @CallbackHandler(data = "admin:exit", filter = isAdmin.class)
        void onCallbackExit(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            bot.clearState(chatId);
            show(bot, chatId, query.message.message_id, "Вы вышли из админ режима.", null, false);
        }

        @CallbackHandler(regex = "approve:.*", filter = isAdmin.class)
        void onCallbackApprove(BotContext bot, CallbackQuery query) throws InterruptedException {
            String[] parts = query.data.split(":");
            long userChatId = Long.parseLong(parts[1]);
            String country = parts.length > 2 ? parts[2] : null;

            Decision decision = configManager.approveConfig(userChatId, country);
            if (decision != Decision.DONE) {
                closeDecidedRequest(bot, query, userChatId, country, decision);
                return;
            }

            answer(bot, query, "Одобрено!");
            String outcome = sendApprovedLinks(bot, userChatId)
                    ? "✅ Конфиг одобрен и отправлен."
                    : "✅ Конфиг одобрен, но отправить пользователю не удалось: возможно, он заблокировал бота.";
            closeRequest(bot, query, userChatId, country, outcome + " Решение: " + adminName(query));
        }

        @CallbackHandler(regex = "reject:.*", filter = isAdmin.class)
        void onCallbackReject(BotContext bot, CallbackQuery query) {
            String[] parts = query.data.split(":");
            long userChatId = Long.parseLong(parts[1]);
            String country = parts.length > 2 ? parts[2] : null;

            Decision decision = configManager.rejectConfig(userChatId, country);
            if (decision != Decision.DONE) {
                closeDecidedRequest(bot, query, userChatId, country, decision);
                return;
            }

            answer(bot, query, "Отклонено.");
            try {
                String title = country != null ? " (" + countryTitle(country) + ")" : "";
                bot.sendMessage(userChatId, "Ваша заявка на конфиг" + title + " отклонена администратором.")
                        .replyMarkup(userMainKeyboard()).exec();
            } catch (RuntimeException e) {
                System.out.println("[Bot] не удалось сообщить " + userChatId + " об отклонении: " + e.getMessage());
            }
            closeRequest(bot, query, userChatId, country, "❌ Заявка отклонена. Решение: " + adminName(query));
        }

        /** Заявку уже закрыл другой админ (или конфиг удалён): новых действий нет, только показываем итог. */
        private void closeDecidedRequest(BotContext bot, CallbackQuery query, long userChatId, String country, Decision decision) {
            switch (decision) {
                case ALREADY_APPROVED -> {
                    answer(bot, query, "Уже одобрено другим админом");
                    closeRequest(bot, query, userChatId, country, "✅ Заявка уже одобрена другим админом.");
                }
                case ALREADY_REJECTED -> {
                    answer(bot, query, "Уже отклонено другим админом");
                    closeRequest(bot, query, userChatId, country, "❌ Заявка уже отклонена другим админом.");
                }
                default -> {
                    answer(bot, query, "Заявка уже неактуальна");
                    closeRequest(bot, query, userChatId, country, "Заявка неактуальна: конфиг уже удалён.");
                }
            }
        }

        /** Показывает решение всем админам и убирает у них кнопки. */
        private void closeRequest(BotContext bot, CallbackQuery query, long userChatId, String country, String outcome) {
            String original = query.message.text != null
                    ? query.message.text.split("\n\n")[0]
                    : "Заявка пользователя " + userChatId;

            Map<Long, Integer> messages = new HashMap<>();
            if (country != null) {
                Map<Long, Integer> sent = adminNotifications.remove(requestKey(userChatId, country));
                if (sent != null) {
                    messages.putAll(sent);
                }
            }
            messages.put(query.message.chat.id, query.message.message_id);

            messages.forEach((adminId, msgId) -> {
                try {
                    show(bot, adminId, msgId, original + "\n\n" + outcome, null, false);
                } catch (RuntimeException e) {
                    System.out.println("[Bot] не удалось обновить уведомление админа " + adminId + ": " + e.getMessage());
                }
            });
        }

        private boolean sendApprovedLinks(BotContext bot, long userChatId) throws InterruptedException {
            try {
                bot.sendMessage(userChatId, formatAllConfigLinks(configManager.getConfigs(userChatId)))
                        .parseMode(ParseMode.MARKDOWN).exec();
                return true;
            } catch (IOException | RuntimeException e) {
                System.out.println("[Bot] не удалось отправить конфиг " + userChatId + ": " + e.getMessage());
                return false;
            }
        }

        private static String adminName(CallbackQuery query) {
            return query.from.username != null ? "@" + query.from.username : "id " + query.from.id;
        }

        private static String requestKey(long userChatId, String country) {
            return userChatId + ":" + country;
        }

        @CallbackHandler(data = "admin:delete", filter = isAdmin.class)
        void onCallbackDelete(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            bot.setState(chatId, STATE_ADMIN_DELETE);
            deleteMessageId.put(chatId, msgId);
            show(bot, chatId, msgId, "Введи имя конфига для удаления (без суффикса _config).\nПример: petr",
                    cancelAdminKeyboard(), false);
        }

        @CallbackHandler(data = "admin:cancel_delete", filter = isAdmin.class)
        void onCallbackCancelDelete(BotContext bot, CallbackQuery query) {
            answer(bot, query, null);
            long chatId = query.from.id;
            bot.setState(chatId, STATE_ADMIN);
            deleteMessageId.remove(chatId);
            show(bot, chatId, query.message.message_id, ADMIN_HOME_TEXT, adminKeyboard(), false);
        }

        @MessageHandler(filter = isAdmin.class, type = MessageType.TEXT, state = STATE_ADMIN_DELETE, priority = 150)
        void onAdminDeleteInput(BotContext bot, Message message) throws IOException, InterruptedException {
            String text = message.text != null ? message.text.trim() : "";
            if (text.startsWith("/")) return;

            long chatId = message.chat.id;
            Integer msgId = deleteMessageId.remove(chatId);
            bot.setState(chatId, STATE_ADMIN);

            try {
                bot.deleteMessage(chatId, message.message_id).exec();
            } catch (Exception ignored) {
            }

            String configName = stripConfigSuffix(text) + CONFIG_NAME_SUFFIX;
            String result;
            try {
                result = configManager.deleteConfig(configName);
            } catch (Exception e) {
                result = "Ошибка при удалении: " + e.getMessage();
            }

            show(bot, chatId, msgId, truncate(ADMIN_HOME_TEXT + "\n\n" + result), adminKeyboard(), false);
        }

        @MessageHandler(type = MessageType.TEXT, priority = 1000)
        void onDefault(BotContext context, Message message) {
            context.sendMessage(message.chat.id,
                    "Я вас не понял. Воспользуйтесь кнопками или напишите /help.").exec();
        }

        private void handleGetConfig(BotContext bot, long chatId, Integer editMsgId) {
            show(bot, chatId, editMsgId, "Выбери страну сервера:", countryKeyboard(), false);
        }

        /** Ответ на callback; устаревший запрос (нажатие пришло, пока бот лежал) не должен прерывать обработку. */
        private static void answer(BotContext bot, CallbackQuery query, String text) {
            try {
                if (text == null) {
                    bot.answerCallbackQuery(query.id).exec();
                } else {
                    bot.answerCallbackQuery(query.id, text).exec();
                }
            } catch (RuntimeException e) {
                System.out.println("[Bot] answerCallbackQuery: " + e.getMessage());
            }
        }

        /**
         * Показывает текст, по возможности обновляя сообщение. Повтор того же текста — не ошибка
         * («message is not modified»), а если сообщение обновить нельзя — отправляет новое.
         */
        private static void show(BotContext bot, long chatId, Integer msgId, String text,
                                 InlineKeyboardMarkup keyboard, boolean markdown) {
            if (msgId != null) {
                try {
                    EditMessageText edit = bot.editMessageText(text, chatId, msgId);
                    if (keyboard != null) edit.replyMarkup(keyboard);
                    if (markdown) edit.parseMode(ParseMode.MARKDOWN);
                    edit.exec();
                    return;
                } catch (BadRequest e) {
                    if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                        return;
                    }
                    System.out.println("[Bot] editMessageText: " + e.getMessage());
                }
            }
            SendMessage send = bot.sendMessage(chatId, text);
            if (keyboard != null) send.replyMarkup(keyboard);
            if (markdown) send.parseMode(ParseMode.MARKDOWN);
            send.exec();
        }

        private void sendConfigResult(BotContext bot, long chatId, String username, String country, String[] configs,
                                      String configName, Integer editMsgId) {
            if (configs.length == 0) {
                String text = "Заявка отправлена!\n\nКонфиг создан и ожидает подтверждения администратора.\nКогда одобрят — нажми кнопку ниже или придёт уведомление.";
                show(bot, chatId, editMsgId, text, checkStatusKeyboard(), false);
                notifyAdmins(bot, chatId, username, configName, country);
            } else {
                String text = "Ваши конфиги готовы!\n\n" + formatConfigLinks(country, configs);
                show(bot, chatId, editMsgId, text, null, true);
            }
        }

        private void notifyAdmins(BotContext bot, long userChatId, String username, String configName, String country) {
            String env = System.getenv("ADMIN_CHATS");
            if (env == null || env.isBlank()) return;

            // Заявка уже у админов (повторный запрос, двойное нажатие) — второе уведомление не нужно
            Map<Long, Integer> sent = new ConcurrentHashMap<>();
            if (adminNotifications.putIfAbsent(requestKey(userChatId, country), sent) != null) return;

            String who = username != null && !username.isBlank() ? "@" + escapeMarkdown(username) : "без @username";
            String text = String.format("Пользователь %s (id: `%d`) хочет создать конфиг `%s` — %s.",
                    who, userChatId, configName, countryTitle(country));

            for (String part : env.split(",")) {
                try {
                    long adminId = Long.parseLong(part.trim());
                    Message message = bot.sendMessage(adminId, text)
                            .parseMode(ParseMode.MARKDOWN)
                            .replyMarkup(approvalKeyboard(userChatId, country)).exec();
                    if (message != null) sent.put(adminId, message.message_id);
                } catch (NumberFormatException ignored) {
                } catch (RuntimeException e) {
                    // Один недоступный админ не должен лишать заявки остальных
                    System.out.println("[Bot] не удалось уведомить админа " + part.trim() + ": " + e.getMessage());
                }
            }
        }

        private String buildSyncResult() {
            try {
                return configManager.syncFromPanel() + "\n\n" + configManager.listUsers();
            } catch (Exception e) {
                return "Ошибка при синхронизации: " + e.getMessage();
            }
        }

        /** Конфиги всех стран пользователя (для «Проверить статус» и уведомления об одобрении). */
        private static String formatAllConfigLinks(Map<String, String[]> byCountry) {
            StringBuilder sb = new StringBuilder("Ваши конфиги готовы!");
            byCountry.forEach((country, configs) ->
                    sb.append("\n\n").append(formatConfigLinks(country, configs)));
            return sb.toString();
        }

        private static String countryTitle(String country) {
            return "germ".equals(country) ? "🇩🇪 Германия" : "🇱🇻 Латвия";
        }

        private static String formatConfigLinks(String country, String[] configs) {
            StringBuilder sb = new StringBuilder("*").append(countryTitle(country)).append("*\n\nПодписка:\n`")
                    .append(configs[1]).append("`");

            if (configs[0] != null) {
                sb.append("\n\nWebSocket конфиг:\n`").append(configs[0]).append("`");
            }

            if (configs.length > 2 && configs[2] != null) {
                sb.append("\n\nxHTTP конфиг:\n`").append(configs[2]).append("`");
            }

            if (configs.length > 3 && configs[3] != null) {
                sb.append("\n\nReality конфиг:\n`").append(configs[3]).append("`");
            }

            return sb.toString();
        }

        private static String stripConfigSuffix(String raw) {
            String t = raw.trim();
            return t.endsWith(CONFIG_NAME_SUFFIX)
                    ? t.substring(0, t.length() - CONFIG_NAME_SUFFIX.length())
                    : t;
        }

        private static String escapeMarkdown(String text) {
            if (text == null || text.isEmpty()) return "";
            return text.replace("\\", "\\\\")
                    .replace("_", "\\_")
                    .replace("*", "\\*")
                    .replace("`", "\\`")
                    .replace("[", "\\[");
        }

        private static String truncate(String text) {
            return text.length() <= 4000 ? text : text.substring(0, 4000) + "\n...";
        }
    }
}