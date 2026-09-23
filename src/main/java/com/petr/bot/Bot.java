package com.petr.bot;

import com.petr.bot.filters.isAdmin;
import com.petr.configmanager.ConfigManager;
import com.petr.configmanager.ConfigManagerImpl;
import io.github.natanimn.telebof.BotClient;
import io.github.natanimn.telebof.BotContext;
import io.github.natanimn.telebof.annotations.CallbackHandler;
import io.github.natanimn.telebof.annotations.MessageHandler;
import io.github.natanimn.telebof.enums.MessageType;
import io.github.natanimn.telebof.enums.ParseMode;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class Bot {
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
        String BOT_TOKEN = System.getenv("BOT_TOKEN");
        if (BOT_TOKEN == null) throw new RuntimeException("BOT_TOKEN не задан");
        bot = new BotClient(BOT_TOKEN);
        bot.context.setMyCommands(commands).exec();
        bot.addHandler(new BotHandler());
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

        private static final String WELCOME_TEXT = """
                Привет!

                Этот бот управляет твоими VPN-конфигами.
                Нажми кнопку ниже, чтобы получить свой конфиг.""";

        private static final String ADMIN_HOME_TEXT = "Панель администратора. Выберите действие:";

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

        private static InlineKeyboardMarkup configTypeKeyboard() {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {
                            new InlineKeyboardButton("WebSocket", "config_type:ws"),
                            new InlineKeyboardButton("xHTTP", "config_type:xhttp"),
                            new InlineKeyboardButton("Оба", "config_type:both")
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

        private static InlineKeyboardMarkup approvalKeyboard(long userChatId) {
            return new InlineKeyboardMarkup(new InlineKeyboardButton[][]{
                    {
                            new InlineKeyboardButton("Одобрить", "approve:" + userChatId),
                            new InlineKeyboardButton("Отклонить", "reject:" + userChatId)
                    }
            });
        }

        @MessageHandler(commands = "start")
        void onStart(BotContext bot, Message message) {
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
            awaitingMessageId.remove(message.chat.id);
            pendingConfigType.remove(message.chat.id);
            pendingCountry.remove(message.chat.id);
            bot.sendMessage(message.chat.id, WELCOME_TEXT).replyMarkup(userMainKeyboard()).exec();
        }

        @MessageHandler(commands = "cancel")
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

            bot.clearState(message.chat.id);
            String configType = pendingConfigType.remove(message.chat.id);
            if (configType == null) configType = "ws";
            String country = pendingCountry.remove(message.chat.id);
            if (country == null) country = "latv";
            Integer editMsgId = awaitingMessageId.remove(message.chat.id);

            try {
                bot.deleteMessage(message.chat.id, message.message_id).exec();
            } catch (Exception ignored) {
            }

            String configName = base + CONFIG_NAME_SUFFIX;
            try {
                String[] configs = configManager.getConfigs(message.chat.id, configName, configType, country);
                sendConfigResult(bot, message.chat.id, country, configs, configName, editMsgId);
            } catch (Exception e) {
                String errText = "Ошибка при создании конфига:\n" + e.getMessage() +
                        "\n\nОбратитесь к администратору.";
                if (editMsgId != null) {
                    bot.editMessageText(errText, message.chat.id, editMsgId)
                            .replyMarkup(userMainKeyboard()).exec();
                } else {
                    bot.sendMessage(message.chat.id, errText).replyMarkup(userMainKeyboard()).exec();
                }
            }
        }

        @CallbackHandler(data = "user:get_config")
        void onCallbackGetConfig(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            int msgId = query.message.message_id;

            if (!configManager.isRegistered(chatId)) {
                bot.editMessageText("Вы не зарегистрированы. Отправьте /start.", chatId, msgId).exec();
                return;
            }

            handleGetConfig(bot, chatId, msgId);
        }

        @CallbackHandler(data = "country:latv")
        void onCallbackCountryLatv(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            // На Риге нет XHTTP — выбирать тип не из чего: WS (+ Reality добавится автоматически)
            requestConfig(bot, query, "ws", "latv");
        }

        @CallbackHandler(data = "country:germ")
        void onCallbackCountryGerm(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            bot.editMessageText(GERMANY_WARNING_TEXT, chatId, msgId)
                    .replyMarkup(germanyWarningKeyboard()).exec();
        }

        @CallbackHandler(data = "germ:confirm")
        void onCallbackGermConfirm(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            pendingCountry.put(chatId, "germ");
            awaitingMessageId.put(chatId, msgId);
            bot.editMessageText("Выбери тип конфига:", chatId, msgId)
                    .replyMarkup(configTypeKeyboard()).exec();
        }

        @CallbackHandler(regex = "config_type:.*")
        void onCallbackConfigType(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            String configType = query.data.split(":")[1];
            String country = pendingCountry.getOrDefault(query.from.id, "latv");
            requestConfig(bot, query, configType, country);
        }

        /** Создаёт/дополняет конфиг; если нет подходящего @username — просит ввести имя. */
        private void requestConfig(BotContext bot, CallbackQuery query, String configType, String country) {
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            pendingCountry.put(chatId, country);
            awaitingMessageId.put(chatId, msgId);

            String username = query.from.username;
            if (username != null && !username.isBlank()) {
                try {
                    String configName = buildConfigName(username);
                    String[] configs = configManager.getConfigs(chatId, configName, configType, country);
                    pendingConfigType.remove(chatId);
                    pendingCountry.remove(chatId);
                    sendConfigResult(bot, chatId, country, configs, configName, msgId);
                    return;
                } catch (IllegalArgumentException ignored) {
                } catch (Exception e) {
                    pendingConfigType.remove(chatId);
                    pendingCountry.remove(chatId);
                    awaitingMessageId.remove(chatId);
                    bot.editMessageText("Ошибка при создании конфига:\n" + e.getMessage() +
                                    "\n\nОбратитесь к администратору.", chatId, msgId)
                            .replyMarkup(userMainKeyboard()).exec();
                    return;
                }
            }

            String existingName = configManager.getExistingConfigName(chatId);
            if (existingName != null) {
                try {
                    String[] configs = configManager.getConfigs(chatId, existingName, configType, country);
                    pendingConfigType.remove(chatId);
                    pendingCountry.remove(chatId);
                    sendConfigResult(bot, chatId, country, configs, existingName, msgId);
                    return;
                } catch (Exception e) {
                    pendingConfigType.remove(chatId);
                    pendingCountry.remove(chatId);
                    bot.editMessageText("Ошибка при создании конфига:\n" + e.getMessage() +
                                    "\n\nОбратитесь к администратору.", chatId, msgId)
                            .replyMarkup(userMainKeyboard()).exec();
                    return;
                }
            }

            pendingConfigType.put(chatId, configType);
            awaitingMessageId.put(chatId, msgId);
            bot.setState(chatId, STATE_AWAITING_CONFIG_NAME);
            bot.editMessageText(
                            "У тебя не задан @username или он не подходит для имени конфига.\n\n" +
                                    "Введи имя одним словом, только латиница (a-z, 0-9, _).\nПример: petr\n\n" +
                                    "Суффикс _config добавлю автоматически.",
                            chatId, msgId)
                    .replyMarkup(cancelKeyboard()).exec();
        }

        @CallbackHandler(data = "user:cancel")
        void onCallbackCancel(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            bot.clearState(chatId);
            awaitingMessageId.remove(chatId);
            pendingConfigType.remove(chatId);
            pendingCountry.remove(chatId);
            bot.editMessageText(WELCOME_TEXT, chatId, query.message.message_id)
                    .replyMarkup(userMainKeyboard()).exec();
        }

        @CallbackHandler(data = "user:check_status")
        void onCallbackCheckStatus(BotContext bot, CallbackQuery query) throws IOException, InterruptedException {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            Map<String, String[]> configs = configManager.getConfigs(chatId);
            if (!configs.isEmpty()) {
                bot.editMessageText(formatAllConfigLinks(configs), chatId, msgId)
                        .parseMode(ParseMode.MARKDOWN).exec();
            } else {
                bot.editMessageText(
                                "Конфиг всё ещё ожидает одобрения администратора.\nОжидайте...",
                                chatId, msgId)
                        .replyMarkup(checkStatusKeyboard()).exec();
            }
        }

        @MessageHandler(filter = isAdmin.class, commands = "admin")
        void onAdmin(BotContext bot, Message message) {
            bot.setState(message.chat.id, STATE_ADMIN);
            bot.sendMessage(message.chat.id, ADMIN_HOME_TEXT)
                    .replyMarkup(adminKeyboard()).exec();
        }

        @MessageHandler(filter = isAdmin.class, commands = "syncPanel")
        void onSyncPanel(BotContext bot, Message message) throws IOException, InterruptedException {
            bot.sendMessage(message.chat.id, buildSyncResult()).exec();
        }

        @MessageHandler(filter = isAdmin.class, commands = "users")
        void onListUsers(BotContext bot, Message message) {
            bot.sendMessage(message.chat.id, configManager.listUsers()).exec();
        }

        @MessageHandler(filter = isAdmin.class, state = STATE_ADMIN, commands = "exAdmin")
        void onExitAdmin(BotContext bot, Message message) {
            bot.clearState(message.chat.id);
            bot.sendMessage(message.chat.id, "Вы вышли из админ режима.").exec();
        }

        @CallbackHandler(data = "admin:sync", filter = isAdmin.class)
        void onCallbackSync(BotContext bot, CallbackQuery query) throws IOException, InterruptedException {
            bot.answerCallbackQuery(query.id, "Синхронизирую...").exec();
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            String result = buildSyncResult();
            bot.editMessageText(truncate(ADMIN_HOME_TEXT + "\n\n" + result), chatId, msgId)
                    .replyMarkup(adminKeyboard()).exec();
        }

        @CallbackHandler(data = "admin:users", filter = isAdmin.class)
        void onCallbackUsers(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            String users = configManager.listUsers();
            bot.editMessageText(truncate(ADMIN_HOME_TEXT + "\n\n" + users), chatId, msgId)
                    .replyMarkup(adminKeyboard()).exec();
        }

        @CallbackHandler(data = "admin:exit", filter = isAdmin.class)
        void onCallbackExit(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            bot.clearState(chatId);
            bot.editMessageText("Вы вышли из админ режима.", chatId, query.message.message_id).exec();
        }

        @CallbackHandler(regex = "approve:.*", filter = isAdmin.class)
        void onCallbackApprove(BotContext bot, CallbackQuery query) throws IOException, InterruptedException {
            long userChatId = Long.parseLong(query.data.split(":")[1]);
            bot.answerCallbackQuery(query.id, "Одобрено!").exec();
            configManager.acceptConfig(userChatId);
            Map<String, String[]> configs = configManager.getConfigs(userChatId);
            if (!configs.isEmpty()) {
                bot.sendMessage(userChatId, formatAllConfigLinks(configs))
                        .parseMode(ParseMode.MARKDOWN).exec();
            }

            bot.editMessageText(
                    "Конфиг пользователя " + userChatId + " одобрен и отправлен.",
                    query.message.chat.id, query.message.message_id).exec();
        }

        @CallbackHandler(regex = "reject:.*", filter = isAdmin.class)
        void onCallbackReject(BotContext bot, CallbackQuery query) {
            long userChatId = Long.parseLong(query.data.split(":")[1]);
            bot.answerCallbackQuery(query.id, "Отклонено.").exec();
            bot.editMessageText(
                    "Запрос от пользователя " + userChatId + " отклонён.",
                    query.message.chat.id, query.message.message_id).exec();
        }

        @CallbackHandler(data = "admin:delete", filter = isAdmin.class)
        void onCallbackDelete(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            int msgId = query.message.message_id;
            bot.setState(chatId, STATE_ADMIN_DELETE);
            deleteMessageId.put(chatId, msgId);
            bot.editMessageText(
                            "Введи имя конфига для удаления (без суффикса _config).\nПример: petr",
                            chatId, msgId)
                    .replyMarkup(cancelAdminKeyboard()).exec();
        }

        @CallbackHandler(data = "admin:cancel_delete", filter = isAdmin.class)
        void onCallbackCancelDelete(BotContext bot, CallbackQuery query) {
            bot.answerCallbackQuery(query.id).exec();
            long chatId = query.from.id;
            bot.setState(chatId, STATE_ADMIN);
            deleteMessageId.remove(chatId);
            bot.editMessageText(ADMIN_HOME_TEXT, chatId, query.message.message_id)
                    .replyMarkup(adminKeyboard()).exec();
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

            if (msgId != null) {
                bot.editMessageText(truncate(ADMIN_HOME_TEXT + "\n\n" + result), chatId, msgId)
                        .replyMarkup(adminKeyboard()).exec();
            } else {
                bot.sendMessage(chatId, result).exec();
            }
        }

        @MessageHandler(type = MessageType.TEXT, priority = 1000)
        void onDefault(BotContext context, Message message) {
            context.sendMessage(message.chat.id,
                    "Я вас не понял. Воспользуйтесь кнопками или напишите /help.").exec();
        }

        private void handleGetConfig(BotContext bot, long chatId, Integer editMsgId) {
            String text = "Выбери страну сервера:";
            if (editMsgId != null) {
                awaitingMessageId.put(chatId, editMsgId);
                bot.editMessageText(text, chatId, editMsgId)
                        .replyMarkup(countryKeyboard()).exec();
            } else {
                Message sent = bot.sendMessage(chatId, text)
                        .replyMarkup(countryKeyboard()).exec();
                if (sent != null) awaitingMessageId.put(chatId, sent.message_id);
            }
        }

        private void sendConfigResult(BotContext bot, long chatId, String country, String[] configs,
                                      String configName, Integer editMsgId) {
            if (configs.length == 0) {
                String text = "Заявка отправлена!\n\nКонфиг создан и ожидает подтверждения администратора.\nКогда одобрят — нажми кнопку ниже или придёт уведомление.";
                if (editMsgId != null) {
                    bot.editMessageText(text, chatId, editMsgId)
                            .replyMarkup(checkStatusKeyboard()).exec();
                } else {
                    bot.sendMessage(chatId, text).replyMarkup(checkStatusKeyboard()).exec();
                }

                notifyAdmins(bot, chatId, configName);
            } else {
                String text = "Ваши конфиги готовы!\n\n" + formatConfigLinks(country, configs);
                if (editMsgId != null) {
                    bot.editMessageText(text, chatId, editMsgId)
                            .parseMode(ParseMode.MARKDOWN).exec();
                } else {
                    bot.sendMessage(chatId, text).parseMode(ParseMode.MARKDOWN).exec();
                }
            }
        }

        private void notifyAdmins(BotContext bot, long userChatId, String configName) {
            String env = System.getenv("ADMIN_CHATS");
            if (env == null || env.isBlank()) return;

            String displayName = escapeMarkdown(stripConfigSuffix(configName));
            String text = String.format(
                    "Пользователь @%s (id: `%d`) хочет создать конфиг.", displayName, userChatId);

            for (String part : env.split(",")) {
                try {
                    long adminId = Long.parseLong(part.trim());
                    bot.sendMessage(adminId, text)
                            .parseMode(ParseMode.MARKDOWN)
                            .replyMarkup(approvalKeyboard(userChatId)).exec();
                } catch (NumberFormatException ignored) {
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

        private static String buildConfigName(String username) {
            String base = stripConfigSuffix(username);
            if (!CONFIG_BASE_NAME_PATTERN.matcher(base).matches()) {
                throw new IllegalArgumentException("Невалидный username: " + base);
            }
            return base + CONFIG_NAME_SUFFIX;
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