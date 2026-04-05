package com.petr.bot;

import com.petr.bot.filters.isAdmin;
import com.petr.bot.filters.userHasConfig;
import com.petr.db.DbService;
import io.github.natanimn.telebof.BotClient;
import io.github.natanimn.telebof.BotContext;
import io.github.natanimn.telebof.annotations.MessageHandler;
import io.github.natanimn.telebof.enums.MessageType;
import io.github.natanimn.telebof.types.updates.Message;

public class Bot {
    final private BotClient bot;

    public Bot(){
        String BOT_TOKEN = System.getenv("BOT_TOKEN");

        if(BOT_TOKEN == null){
            throw new RuntimeException("BOT_TOKEN environment variable not set");
        }
        bot = new BotClient(BOT_TOKEN);
        bot.addHandler(new BotHandler());
    }

    public void startBot(){
        bot.startPolling();
    }

    class BotHandler{
        @MessageHandler(
                commands = "start"
        )
        void onStart(BotContext bot, Message message){
            bot.sendMessage(message.chat.id, """
                Привет! Зарегистрировал тебя.
                
                Этот бот - менеджер твоих конфигов.
                Если не помнишь как им пользоваться - введи команду /help
                """).exec();
            //TODO добавление пользователя в бд здесь
        }

        @MessageHandler(
                commands = "meow"
        )
        void onMeow(BotContext bot, Message message){
            bot.sendMessage(message.chat.id, """
                meow 🐈
                """).exec();
        }

        //TODO обновить по ходу разработки
        @MessageHandler(
                commands = "help"
        )
        void onHelp(BotContext bot, Message message){
            bot.sendMessage(message.chat.id, """
                Команды бота:
                /help - Информационное сообщение
                /get_config - Создать новый или получить свой конфиг
                """).exec();
        }

        //TODO написать фильтр и хэндлер получения конфига из бд
        @MessageHandler(
                priority = 1,
                commands = "get_config",
                filter = userHasConfig.class
        )
        void userHasConfig(BotContext bot, Message message){
            bot.sendMessage(message.chat.id, """
                команда приоритет 1
                """).exec();
        }

        //TODO написать хэндлер создания конфига
        @MessageHandler(
                priority = 2,
                commands = "get_config"
        )
        void createConfig(BotContext bot, Message message){
            bot.sendMessage(message.chat.id, """
                команда приоритет 2
                """).exec();
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
                """).exec(); //TODO изменить по ходу разработки
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

        //FIXME если не работает - изменить приоритет сообщения
        @MessageHandler(
                type = MessageType.TEXT
        )
        void onDefault(BotContext context, Message message){
            context.sendMessage(message.chat.id, """
                Я вас не понял, напишите /help чтобы увидеть доступные команды.
                """).exec();
        }
    }
}
