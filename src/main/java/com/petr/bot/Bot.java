package com.petr.bot;

import io.github.natanimn.telebof.BotClient;

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
        // TODO написать handler
    }
}
