package com.petr.bot.filters;

import io.github.natanimn.telebof.filters.CustomFilter;
import io.github.natanimn.telebof.types.updates.Update;

public class isAdmin implements CustomFilter {

    @Override
    public boolean check(Update update) {
        if (update.message == null || update.message.text == null) {
            return false;
        }

        Long[] adminChats = {
                Long.parseLong(System.getenv("ADMIN_CHATS").split(",")[0]),
                Long.parseLong(System.getenv("ADMIN_CHATS").split(",")[1])
        };

        boolean isAdmin = false;

        if(adminChats[0] == update.message.chat.id || adminChats[1] == update.message.chat.id){
            isAdmin = true;
        }

        return isAdmin;
    }
}
