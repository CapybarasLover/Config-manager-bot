package com.petr.bot;

import com.petr.bot.filters.isAdmin;
import com.petr.configmanager.ConfigManager;
import com.petr.configmanager.ConfigManagerImpl;
import io.github.natanimn.telebof.BotClient;
import io.github.natanimn.telebof.BotContext;
import io.github.natanimn.telebof.annotations.MessageHandler;
import io.github.natanimn.telebof.enums.MessageType;
import io.github.natanimn.telebof.enums.ParseMode;
import io.github.natanimn.telebof.types.bot.BotCommand;
import io.github.natanimn.telebof.types.updates.Message;

import java.io.IOException;
import java.util.regex.Pattern;

public class Bot {
    final private BotClient bot;
    final private ConfigManager configManager = new ConfigManagerImpl();

    final private BotCommand[] commands = {
            new BotCommand("help", "Информационное сообщение"),
            new BotCommand("get_config", "Создать новый или уже существующий конфиг"),
            new BotCommand("cancel", "Отменить ввод имени конфига (если бот его ждёт)"),
            new BotCommand("start", "Вводится только в начале для регистрации пользователя")
    };

    public Bot(){
        String BOT_TOKEN = System.getenv("BOT_TOKEN");

        if(BOT_TOKEN == null){
            throw new RuntimeException("BOT_TOKEN environment variable not set");
        }
        bot = new BotClient(BOT_TOKEN);

        bot.context.setMyCommands(commands).exec();

        bot.addHandler(new BotHandler());
    }

    public void startBot(){
        bot.startPolling();
    }

    class BotHandler {

        private static final String STATE_AWAITING_CONFIG_NAME = "awaiting_config_name";
        private static final String CONFIG_NAME_SUFFIX = "_config";

        /** Только «основа» имени латиницей; суффикс {@code _config} бот добавляет сам. */
        private static final Pattern CONFIG_BASE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

        @MessageHandler(
                commands = "start"
        )
        void onStart(BotContext bot, Message message){
            //добавление пользователя в бд здесь, если не существует
            System.out.println(configManager.onStart(message.chat.id, message.chat.username));
            System.out.println(message.chat.username);
            bot.sendMessage(message.chat.id, """
                Привет!
                
                Этот бот - менеджер твоих конфигов.
                Если не помнишь как им пользоваться - введи команду /help
                """).exec();
        }

        @MessageHandler(
                commands = "meow"
        )
        void onMeow(BotContext bot, Message message){
            bot.sendMessage(message.chat.id, """
                meow 🐈
                """).exec();
        }

        @MessageHandler(
                commands = "help"
        )
        void onHelp(BotContext bot, Message message){
            bot.sendMessage(message.chat.id, """
                Команды бота:
                /help - Информационное сообщение
                /get_config - Создать новый или получить свой конфиг
                /cancel - Отменить ввод имени конфига (если бот его ждёт)
                """).exec();
        }

        @MessageHandler(
                commands = "get_config"
        )
        void onGetConfig(BotContext bot, Message message) throws IOException, InterruptedException {
            bot.clearState(message.chat.id);
            if(!configManager.isRegistered(message.chat.id)){
                bot.sendMessage(message.chat.id, """
                        К сожалению кажется вы не зарегестрированы в базе данных.
                        
                        Отправьте /start чтобы бот запомнил вас)
                        """).exec();
                return;
            }
            String username = message.chat.username;
            if (username != null && !username.isBlank()) {
                try {
                    runGetConfig(bot, message.chat.id, buildConfigClientName(username), false);
                } catch (IllegalArgumentException ex) {
                    bot.setState(message.chat.id, STATE_AWAITING_CONFIG_NAME);
                    bot.sendMessage(message.chat.id, """
                            Ваш @username нельзя использовать как имя конфига автоматически.
                            Введите имя латиницей одним сообщением (суффикс _config добавлю сам). Отмена: /cancel
                            """).exec();
                }
            } else {
                bot.setState(message.chat.id, STATE_AWAITING_CONFIG_NAME);
                bot.sendMessage(message.chat.id, """
                        У вас в Telegram не задан публичный @username, поэтому имя конфига нужно указать вручную.

                        Отправьте одним сообщением только имя латиницей (буквы, цифры, _), например: petr

                        Отмена: /cancel
                        """).exec();
            }
        }

        @MessageHandler(
                commands = "cancel",
                state = STATE_AWAITING_CONFIG_NAME
        )
        void onCancelConfigName(BotContext bot, Message message) {
            bot.clearState(message.chat.id);
            bot.sendMessage(message.chat.id, """
                    Ввод имени конфига отменён. Чтобы попробовать снова: /get_config
                    """).exec();
        }

        @MessageHandler(
                type = MessageType.TEXT,
                state = STATE_AWAITING_CONFIG_NAME,
                priority = 200
        )
        void onAwaitingConfigName(BotContext bot, Message message) throws IOException, InterruptedException {
            String text = message.text != null ? message.text.trim() : "";
            if (text.startsWith("/")) {
                bot.sendMessage(message.chat.id, """
                        Сейчас ожидается имя латиницей (без _config) или команда /cancel.
                        """).exec();
                return;
            }
            String base = stripOptionalConfigSuffix(text);
            if (!CONFIG_BASE_NAME_PATTERN.matcher(base).matches()) {
                bot.sendMessage(message.chat.id, """
                        Неверный формат. Укажите только имя латиницей (с цифрами и _), например: petr
                        """).exec();
                return;
            }
            bot.clearState(message.chat.id);
            runGetConfig(bot, message.chat.id, base + CONFIG_NAME_SUFFIX, false);
        }

        /**
         * Убирает хвост {@code _config}, если пользователь всё же его указал — чтобы не получилось {@code petr_config_config}.
         */
        private static String stripOptionalConfigSuffix(String raw) {
            String t = raw.trim();
            if (t.endsWith(CONFIG_NAME_SUFFIX)) {
                return t.substring(0, t.length() - CONFIG_NAME_SUFFIX.length());
            }
            return t;
        }

        private static String buildConfigClientName(String telegramUsername) {
            String base = stripOptionalConfigSuffix(telegramUsername);
            if (!CONFIG_BASE_NAME_PATTERN.matcher(base).matches()) {
                throw new IllegalArgumentException("Некорректный @username для имени конфига: " + base);
            }
            return base + CONFIG_NAME_SUFFIX;
        }

        private static String escapeMarkdown(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            return text
                    .replace("\\", "\\\\")
                    .replace("_", "\\_")
                    .replace("*", "\\*")
                    .replace("`", "\\`")
                    .replace("[", "\\[");
        }

        private void runGetConfig(BotContext bot, Long id, String configName, boolean idFromAdmin)
                throws IOException, InterruptedException {
            String[] configs;
            if(idFromAdmin){
                configs = configManager.getConfigs(id);
            } else {
                configs = configManager.getConfigs(id, configName);
            }
            sendConfigLinks(bot, id, configs, configName);
        }

        private void sendConfigLinks(BotContext bot, long chatId, String[] configs, String username) {
            if (configs.length == 0) {
                bot.sendMessage(chatId, """
                        Ваш конфиг создан, но еще подтвержден админом.
                        Ожидайте...
                        """).exec();
                Long[] adminChats = {
                        Long.parseLong(System.getenv("ADMIN_CHATS").split(",")[0]),
                        Long.parseLong(System.getenv("ADMIN_CHATS").split(",")[1])
                };
                String displayUsername = stripOptionalConfigSuffix(username);
                String safeUsername = escapeMarkdown(displayUsername);
                String safeChatId = escapeMarkdown(String.valueOf(chatId));
                for(var admin : adminChats){
                    bot.sendMessage(admin, String.format("""
                                    Пользователь с ником @%s (userid: %s) хочет
                                    создать конфиг.
                                    
                                    войдите в админ режим /admin
                                    
                                    и затем пришлите `Yey, %s`, если согласны,
                                    или `Nay, %s`, если нет.
                                    """, safeUsername, safeChatId, safeChatId, safeChatId)
                            ).parseMode(ParseMode.MARKDOWN).exec();
                }
            } else {
                bot.sendMessage(chatId, String.format("""
                        Ваши конфиги (нажмите на одну из ссылок,
                        чтобы скопировать):

                        Подписка:
                        `%s`

                        Конфиг:
                        `%s`
                        """, configs[1], configs[0])).parseMode(ParseMode.MARKDOWN).exec();
            }
        }

        @MessageHandler(
                filter = isAdmin.class,
                commands = "admin"
        )
        void onAdmin(BotContext bot, Message message){
            bot.setState(message.chat.id, "admin_state");
            bot.sendMessage(message.chat.id, """
                Добро пожаловать, Админ!
                Список админских команд:
                /admin - войти в админ режим
                
                /delConfig - удалить конфиг по юзернейму
                
                /exAdmin - выйти из админ режима
                """).exec();
        }

        @MessageHandler(
                filter = isAdmin.class,
                state = "admin_state"
        )
        void onAdminMessage(BotContext bot, Message message) throws IOException, InterruptedException {
            Long userChatId = Long.parseLong(message.text.split(", ")[1]);
            if(message.text.contains("Yey")){
                configManager.acceptConfig(userChatId);
                runGetConfig(bot, userChatId, "", true);
            } else if(message.text.contains("Nay")){

            }
        }

        @MessageHandler(
                filter = isAdmin.class,
                state = "admin_state",
                commands = "exAdmin"
        )
        void onExitAdmin(BotContext bot, Message message){
            bot.clearState(message.chat.id);
            bot.sendMessage(message.chat.id, """
                Вы вышли из админ режима!
                """).exec();
        }

        @MessageHandler(
                type = MessageType.TEXT,
                priority = 1000
        )
        void onDefault(BotContext context, Message message){
            context.sendMessage(message.chat.id, """
                Я вас не понял, напишите /help чтобы увидеть доступные команды.
                """).exec();
        }
    }
}
