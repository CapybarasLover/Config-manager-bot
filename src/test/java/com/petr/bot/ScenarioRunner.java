package com.petr.bot;

import com.petr.bot.FakeTelegram.Msg;
import com.petr.db.migration.DatabaseMigration;
import io.github.natanimn.telebof.BotClient;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;

/**
 * Прогон всех пользовательских и админских сценариев через настоящий {@link Bot}:
 * Telegram и обе панели подменены локальными фейками, БД — отдельный Postgres.
 * Запуск: scripts/run-scenarios.sh. К настоящим панелям и Telegram не обращается.
 */
public final class ScenarioRunner {

    private static final long ADMIN_A = 9001;
    private static final long ADMIN_B = 9002;
    private static final String GERM_SUB = "https://germ.test:2096/sub/";
    private static final String LATV_SUB = "https://latv.test:2096/sub/";
    private static final String POL_SUB = "https://pol.test:2096/sub/";

    private static PrintStream report;
    private static FakeTelegram tg;
    private static FakePanel latv;
    private static FakePanel germ;
    private static FakePanel pol;
    private static BotClient client;
    private static Connection db;

    private static long nextUserId = 100;
    private static String current = "";
    private static int passed = 0;
    private static final List<String> failed = new ArrayList<>();
    private static final List<String> expectedApiErrors = new ArrayList<>();
    private static final List<String> crashes = java.util.Collections.synchronizedList(new ArrayList<>());

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        report = System.out;
        try {
            run(args);
        } catch (Throwable t) {
            report.println("Стенд не запустился: " + t);
            t.printStackTrace(report);
            report.flush();
            System.exit(2); // потоки HttpServer не дают JVM завершиться самой
        }
    }

    private static void run(String[] args) throws Exception {
        guardAgainstRealServices();
        PrintStream log = new PrintStream(new FileOutputStream(args.length > 0 ? args[0] : "scenario-bot.log"), true, StandardCharsets.UTF_8);
        System.setOut(log);
        System.setErr(log);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            crashes.add(String.valueOf(e));
            e.printStackTrace(log);
        });

        tg = new FakeTelegram(18083);
        latv = new FakePanel(18081, "latv-token", "latv.test")
                .inbound(1, 1235, "ws", "none").inbound(4, 58549, "ws", "tls")
                .inbound(6, 17827, "tcp", "reality").inbound(8, 443, "tcp", "reality");
        germ = new FakePanel(18082, "germ-token", "germ.test")
                .inbound(2, 54228, "xhttp", "reality").inbound(3, 1235, "ws", "none")
                .inbound(4, 50136, "xhttp", "tls").inbound(5, 443, "tcp", "reality");
        // Как на настоящей Варшаве: VLESS Encryption на всех трёх, поэтому Vision доступен везде
        pol = new FakePanel(18084, "pol-token", "pol.test")
                .inbound(1, 2087, "ws", "none", true).inbound(2, 8443, "xhttp", "reality", true)
                .inbound(3, 443, "tcp", "reality", true);

        DatabaseMigration.startMigration(System.getProperty("app.env"));
        db = DriverManager.getConnection(System.getenv("DB_LINK_PROD"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
        startBot();

        warmUp();
        runAll();

        report.println();
        report.println("Итого: проверок пройдено " + passed + ", провалено " + failed.size());
        failed.forEach(f -> report.println("  ✘ " + f));
        report.flush();
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    private static void guardAgainstRealServices() {
        for (String name : List.of("LATV_PANEL_HOME_URL", "GERMAN_PANEL_HOME_URL", "POL_PANEL_HOME_URL", "DB_LINK_PROD")) {
            String v = System.getenv(name);
            if (v == null || !v.contains("127.0.0.1")) {
                throw new IllegalStateException(name + " должен указывать на 127.0.0.1 — запускайте через scripts/run-scenarios.sh");
            }
        }
    }

    private static void startBot() {
        client = Bot.buildClient(new BotClient.Builder("000:test").localBotApiUrl("http://127.0.0.1:18083/bot%s/%s"));
        Bot bot = new Bot(client);
        Thread t = new Thread(bot::startBot, "bot-polling");
        t.setDaemon(true);
        t.start();
    }

    private static void restartBot() throws Exception {
        client.stop();
        Thread.sleep(400);
        startBot();
        Thread.sleep(400);
    }

    private static void warmUp() throws Exception {
        // Первый запрос к БД на холодной JVM идёт дольше паузы, по которой settle() считает бота свободным
        U u = user("warmup");
        u.say("/start");
        Thread.sleep(2500);
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Thread.sleep(1500);
        settle();
        reset();
        failed.clear();
        passed = 0;
    }

    private static void runAll() {
        scenario("Регистрация: /start, повторный /start", ScenarioRunner::registration);
        scenario("Незарегистрированный: /get_config и кнопка", ScenarioRunner::unregistered);
        scenario("Справка: /help, /how_to_use, /cancel, произвольный текст", ScenarioRunner::helpCommands);
        scenario("Латвия: заявка, статус, одобрение, синхронизация админов", ScenarioRunner::latviaFullFlow);
        scenario("Германия: предупреждение и отмена на каждом шаге", ScenarioRunner::germanyCancel);
        scenario("Германия: новые конфиги WS / xHTTP / Оба", ScenarioRunner::germanyTypes);
        scenario("Польша: кнопка, отмена, конфиги WS / xHTTP / Оба", ScenarioRunner::polandFlow);
        scenario("Польша: своё одобрение, новые типы без заявки", ScenarioRunner::polandSeparateApproval);
        scenario("XTLS Vision: новый Reality с flow, старый без flow не трогаем", ScenarioRunner::visionFlow);

        scenario("Оба: клавиатура осталась от прошлого запроса (случай с прода)", ScenarioRunner::bothOnStaleKeyboard);
        scenario("Двойное нажатие на каждом шаге", ScenarioRunner::doubleTapEveryStep);
        scenario("Оба: старый WS-конфиг", () -> bothOnOld("x_config", new Integer[]{3}, "x_config", true, false, false, null, true));
        scenario("Оба: xHTTP новой модели", () -> bothOnOld("x_config", new Integer[]{2}, "x_config", false, true, false, null, true));
        scenario("Оба: xHTTP+Reality", () -> bothOnOld("x_config", new Integer[]{2, 5}, "x_config", false, true, true, null, true));
        scenario("Оба: только Reality на панели", () -> bothOnOld("x_config", new Integer[]{5}, "x_config", false, false, false, null, true));
        scenario("Оба: старая пара _config[3] + _xhttp[2]", () -> bothOnOld("x_config", new Integer[]{3}, "x_config", true, true, false, new Integer[]{2}, true));
        scenario("Оба: уже всё привязано, ссылки в БД устарели", () -> bothOnOld("x_config", new Integer[]{2, 3, 5}, "x_config", false, true, false, null, true));
        scenario("Оба: на панели только старый _xhttp[2]", () -> bothOnOld(null, null, "x_config", false, true, false, new Integer[]{2}, false));
        scenario("Оба: в БД имя x_xhttp, на панели есть пара", () -> bothOnOld("x_config", new Integer[]{2, 3, 5}, "x_xhttp", false, true, false, new Integer[]{2}, true));
        scenario("Оба: в БД имя x_xhttp, пары нет", () -> bothOnOld(null, null, "x_xhttp", false, true, false, new Integer[]{2}, false));
        scenario("Оба: на панели клиента нет вовсе", () -> bothOnOld(null, null, "x_config", true, true, false, null, false));

        scenario("Латвия: старый WS-конфиг получает Reality", ScenarioRunner::latviaOldWs);
        scenario("Латвия: в БД имя x_xhttp", ScenarioRunner::latviaOldXhttpName);
        scenario("Смена @username: остаётся старый клиент", ScenarioRunner::usernameChanged);
        scenario("Лишние inbound'ы: берутся ссылки настроенных", ScenarioRunner::foreignInbounds);
        scenario("Без @username: ввод имени, ошибки, подсказки", ScenarioRunner::noUsername);
        scenario("Без @username: отмена кнопкой и /cancel", ScenarioRunner::noUsernameCancel);
        scenario("Занятое имя не отдаёт чужой конфиг", ScenarioRunner::nameTaken);
        scenario("Вторая страна требует отдельного одобрения", ScenarioRunner::secondCountry);
        scenario("Повторный запрос одобренного конфига", ScenarioRunner::repeatApproved);
        scenario("Разрешение на страну: новые типы без заявки", ScenarioRunner::approvalIsPerCountry);
        scenario("Повторный запрос ожидающего не дублирует уведомления", ScenarioRunner::repeatPending);
        scenario("Двойное нажатие «Оба»", ScenarioRunner::doubleClick);
        scenario("Панель недоступна и потом восстановилась", ScenarioRunner::panelDown);
        scenario("Отклонение: админы и пользователь в курсе, можно подать снова", ScenarioRunner::rejectFlow);
        scenario("Одобрение после удаления конфига", ScenarioRunner::approveAfterDelete);
        scenario("Два админа нажимают «Одобрить» одновременно", ScenarioRunner::simultaneousApprove);
        scenario("Одобрение и отклонение одной заявки", ScenarioRunner::approveVsReject);
        scenario("Кнопки старого формата после обновления бота", ScenarioRunner::legacyButtons);
        scenario("Пользователь заблокировал бота", ScenarioRunner::userBlockedBot);
        scenario("Один из админов недоступен", ScenarioRunner::adminUnreachable);
        scenario("Рестарт бота при выборе типа: страна не теряется", ScenarioRunner::restartKeepsCountry);
        scenario("Рестарт бота при ожидании одобрения", ScenarioRunner::restartDuringApproval);
        scenario("Админ-панель: кнопки, команды, повторные нажатия", ScenarioRunner::adminPanel);
        scenario("Не-админ не может выполнять админские действия", ScenarioRunner::nonAdmin);
        scenario("Синхронизация: заявки не одобряются сами, ссылки обновляются", ScenarioRunner::sync);
        scenario("Удаление: формы имени, ошибки, отмена", ScenarioRunner::deleteBasics);
        scenario("Удаление: конфиг во всех странах и старая пара _xhttp", ScenarioRunner::deleteBothCountries);
        scenario("Удаление при недоступной панели", ScenarioRunner::deletePanelDown);
        scenario("Username с подчёркиваниями в Markdown", ScenarioRunner::markdownUsername);
        scenario("Длинный список пользователей", ScenarioRunner::longUserList);
        scenario("Устаревший callback не обрывает обработку", ScenarioRunner::staleCallback);
        scenario("«Проверить статус» показывает актуальные ссылки", ScenarioRunner::statusRefreshesLinks);
        scenario("Миграция данных: статусы и имена старых конфигов", ScenarioRunner::dataMigration);
        scenario("Панель зависла: ошибка за конечное время, бот отвечает другим", ScenarioRunner::panelHangs);
    }

    // ── Сценарии ─────────────────────────────────────────────────────────────

    private static void registration() throws Exception {
        U u = user("petr");
        u.say("/start");
        check("приветствие", u.last().text.contains("Привет"));
        check("кнопка «Получить конфиг»", u.last().buttons.containsKey("Получить конфиг"));
        check("пользователь в БД ждёт", "w".equals(sql("SELECT wait_accept FROM tg_user WHERE id=?", u.id)));
        u.say("/start");
        check("повторный /start не дублирует", "1".equals(sql("SELECT count(*) FROM tg_user WHERE id=?", u.id)));
        U renamed = new U(u.id, "petr_new");
        renamed.say("/start");
        check("/start обновляет username", "petr_new".equals(sql("SELECT tg_name FROM tg_user WHERE id=?", u.id)));
    }

    private static void unregistered() throws Exception {
        U u = user("ghost");
        u.say("/get_config");
        check("просит /start", u.last().text.contains("/start"));
        tg.click(u.id, u.name, u.last().id, u.last().text, "user:get_config");
        settle();
        check("кнопка тоже просит /start", u.last().text.contains("/start"));
        tg.click(u.id, u.name, u.last().id, u.last().text, "country:latv");
        settle();
        check("старая кнопка страны не создаёт клиента", latv.clients.isEmpty());
        check("и тоже просит /start", u.last().text.contains("/start"));
    }

    private static void helpCommands() throws Exception {
        U u = registered("helpme");
        u.say("/help");
        check("/help", u.last().text.contains("/get_config"));
        u.say("/how_to_use");
        check("/how_to_use шлёт картинку", tg.photos.get() == 1);
        u.say("/cancel");
        check("/cancel без контекста", u.last().text.contains("Нечего отменять"));
        u.say("просто текст");
        check("непонятный текст", u.last().text.contains("не понял"));
    }

    private static void latviaFullFlow() throws Exception {
        U u = registered("petr");
        u.say("/get_config");
        check("выбор страны", u.last().buttons.containsKey("🇱🇻 Латвия") && u.last().buttons.containsKey("🇩🇪 Германия"));
        u.press("🇱🇻 Латвия");

        check("клиент на панели в WS+Reality", latv.inboundsOf("petr_config").equals(ids(1, 8)));
        check("Reality с XTLS Vision", FakePanel.VISION.equals(latv.flowOf("petr_config", 8)));
        check("на WS flow нет", latv.flowOf("petr_config", 1).isEmpty());
        check("на панели записан tgId", latv.clients.get("petr_config").tgId == u.id);
        check("пользователю — «ожидает»", u.last().text.contains("ожидает"));
        check("ссылок до одобрения нет", !u.last().text.contains("vless://"));
        check("кнопка статуса", u.last().buttons.containsKey("Проверить статус"));

        Msg a = notification(ADMIN_A, u.id);
        Msg b = notification(ADMIN_B, u.id);
        check("оба админа уведомлены", a != null && b != null);
        check("в уведомлении username и страна", a != null && a.text.contains("petr") && a.text.contains("Латвия"));
        check("в уведомлении кнопки решения", a != null && a.buttons.size() == 2);

        u.press("Проверить статус");
        check("статус: всё ещё ждёт", u.last().text.contains("ожидает"));
        u.press("Проверить статус");
        check("повторный статус не сломал сообщение", u.last().buttons.containsKey("Проверить статус"));

        String staleData = b.buttons.get("Одобрить");
        adminPress(ADMIN_A, a, "Одобрить");
        Msg links = lastWith(u.id, "vless://");
        check("пользователь получил ссылки", links != null);
        if (links != null) expectLinks(links, true, false, true);
        check("в ссылке Reality есть flow", links != null && links.text.contains("flow=" + FakePanel.VISION));
        check("у нажавшего админа кнопки убраны", a.buttons.isEmpty());
        check("нажавшему видно «одобрен»", a.text.contains("одобрен"));
        check("второму админу видно «одобрен»", b.text.contains("одобрен"));
        check("у второго админа кнопки убраны", b.buttons.isEmpty());
        check("второму видно, кто одобрил", b.text.contains("admin_" + ADMIN_A));

        int before = countWith(u.id, "vless://");
        String q = tg.click(ADMIN_B, "admin_" + ADMIN_B, b.id, b.text, staleData);
        settle();
        check("повторное одобрение не шлёт конфиг снова", countWith(u.id, "vless://") == before);
        check("второму — всплывашка «уже»", tg.answers.getOrDefault(q, "").toLowerCase().contains("уже"));

        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        check("одобренному — сразу ссылки", u.last().text.contains("vless://"));
    }

    private static void germanyCancel() throws Exception {
        U u = registered("germcancel");
        u.say("/get_config");
        u.press("Отмена");
        check("отмена на выборе страны", u.last().text.contains("Привет"));
        u.say("/get_config");
        u.press("🇩🇪 Германия");
        check("предупреждение о торрентах", u.last().text.contains("торрент"));
        u.press("❌ Отмена");
        check("отмена на предупреждении", u.last().text.contains("Привет"));
        u.say("/get_config");
        u.press("🇩🇪 Германия");
        u.press("✅ Принимаю условия");
        check("выбор типа", u.last().buttons.containsKey("Все"));
        u.press("Отмена");
        check("отмена на выборе типа", u.last().text.contains("Привет"));
        check("панель не тронута", germ.clients.isEmpty());
        check("админов не беспокоили", notification(ADMIN_A, u.id) == null);
    }

    private static void germanyTypes() throws Exception {
        germType("ws1", "WebSocket", ids(3), true, false, false);
        germType("xh1", "xHTTP", ids(2), false, true, false);
        germType("re1", "Reality", ids(5), false, false, true);
        germType("both1", "Все", ids(2, 3, 5), true, true, true);
    }

    private static void germType(String name, String button, Set<Integer> inbounds, boolean ws, boolean xhttp, boolean reality) throws Exception {
        U u = registered(name);
        requestGerm(u, button);
        check(button + ": inbound'ы", germ.inboundsOf(name + "_config").equals(inbounds));
        check(button + ": Vision только на Reality", germ.flowOf(name + "_config", 5).equals(reality ? FakePanel.VISION : "")
                && germ.flowOf(name + "_config", 2).isEmpty() && germ.flowOf(name + "_config", 3).isEmpty());
        Msg n = notification(ADMIN_A, u.id);
        check(button + ": заявка у админа", n != null);
        if (n != null) adminPress(ADMIN_A, n, "Одобрить");
        Msg links = lastWith(u.id, "vless://");
        check(button + ": ссылки после одобрения", links != null);
        if (links != null) expectLinks(links, ws, xhttp, reality);
    }

    private static void polandFlow() throws Exception {
        U u = registered("warsaw");
        u.say("/get_config");
        check("кнопка Польши", u.last().buttons.containsKey("🇵🇱 Польша"));
        u.press("🇵🇱 Польша");
        check("без предупреждения о торрентах", !u.last().text.contains("торрент"));
        check("выбор типа: по кнопке на протокол и «Все»", u.last().buttons.keySet().containsAll(
                List.of("WebSocket", "xHTTP", "Reality", "Все")));
        u.press("Отмена");
        check("отмена на выборе типа", u.last().text.contains("Привет"));
        check("панель Польши не тронута", pol.clients.isEmpty());

        polType("pws", "WebSocket", ids(1), true, false, false);
        polType("pxh", "xHTTP", ids(2), false, true, false);
        polType("pre", "Reality", ids(3), false, false, true);
        polType("pboth", "Все", ids(1, 2, 3), true, true, true);
        check("Латвия и Германия не тронуты", latv.clients.isEmpty() && germ.clients.isEmpty());
    }

    private static void polType(String name, String button, Set<Integer> inbounds, boolean ws, boolean xhttp, boolean reality) throws Exception {
        U u = registered(name);
        requestPol(u, button);
        check(button + ": inbound'ы в Польше", pol.inboundsOf(name + "_config").equals(inbounds));
        check(button + ": Vision на всех (VLESS Encryption)", inbounds.stream()
                .allMatch(id -> FakePanel.VISION.equals(pol.flowOf(name + "_config", id))));
        FakePanel.Client c = pol.clients.get(name + "_config");
        check(button + ": на панели записан tgId", c != null && c.tgId == u.id);
        check(button + ": пользователю — «ожидает»", u.last().text.contains("ожидает"));
        Msg n = notification(ADMIN_A, u.id);
        check(button + ": заявка у админа про Польшу", n != null && n.text.contains("Польша"));
        if (n != null) adminPress(ADMIN_A, n, "Одобрить");
        Msg links = lastWith(u.id, "pol.test");
        check(button + ": ссылки Польши после одобрения", links != null && links.text.contains("Польша"));
        if (links != null) expectLinks(links, ws, xhttp, reality);
    }

    private static void polandSeparateApproval() throws Exception {
        U u = registered("plus");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        adminPress(ADMIN_A, notification(ADMIN_A, u.id), "Одобрить");

        requestPol(u, "WebSocket");
        check("Польша — снова заявка", u.last().text.contains("ожидает"));
        Msg n = notification(ADMIN_A, u.id);
        check("админу пришла заявка на Польшу", n != null && n.text.contains("Польша"));
        u.press("Проверить статус");
        check("статус говорит, что Польша ждёт", u.last().text.contains("Польша") && u.last().text.contains("ожидает"));
        check("статус не отдаёт неодобренную Польшу", !u.last().text.contains("pol.test"));
        check("статус отдаёт одобренную Латвию", u.last().text.contains("latv.test"));

        if (n != null) adminPress(ADMIN_A, n, "Одобрить");
        Msg links = lastWith(u.id, "pol.test");
        check("после одобрения пришла Польша", links != null);
        check("вместе с Латвией", links != null && links.text.contains("latv.test"));

        requestPol(u, "Все");
        check("в одобренной Польше xHTTP сразу", u.last().text.contains("xHTTP конфиг"));
        check("новой заявки нет", countWithExact(ADMIN_A, "approve:" + u.id) == 0);
        check("клиент Польши во всех inbound'ах", pol.inboundsOf("plus_config").equals(ids(1, 2, 3)));
        FakePanel.Client c = pol.clients.get("plus_config");
        check("подписка не поменялась", c != null && u.last().text.contains(POL_SUB + c.subId));
    }

    /**
     * Без XTLS Vision Reality по TCP быстро детектится. Но Xray отклоняет ссылку без flow, если на сервере
     * у клиента Vision, поэтому Reality, который у пользователя уже был без flow, бот не трогает.
     */
    private static void visionFlow() throws Exception {
        // Бот-клиент только с WS: на WS Vision не ставится, Reality появляется позже
        U u = registered("vis");
        requestGerm(u, "WebSocket");
        adminPress(ADMIN_A, notification(ADMIN_A, u.id), "Одобрить");
        check("на WS flow нет", germ.flowOf("vis_config", 3).isEmpty());
        requestGerm(u, "Reality");
        check("новый Reality с Vision", FakePanel.VISION.equals(germ.flowOf("vis_config", 5)));
        check("WS остался без flow", germ.flowOf("vis_config", 3).isEmpty());
        check("в ответе ссылка Reality с Vision", u.last().text.contains("flow=" + FakePanel.VISION));

        // Новый клиент сразу с Reality: Vision ставится при создании, без отдельного запроса
        U fresh = registered("visnew");
        requestGerm(fresh, "Все");
        check("Vision с создания", FakePanel.VISION.equals(germ.flowOf("visnew_config", 5)));
        check("без отдельного bulkAdjust", germ.writes.stream().noneMatch(w -> w.startsWith("bulkAdjust visnew_config")));

        // Старый клиент: Reality уже был без flow — не трогаем, иначе импортированная ссылка перестанет работать
        U old = registered("oldvis");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", old.id);
        germ.put("oldvis_config", "uuid-o", "o-sub", old.id, 3, 5);
        dbConfig(old.id, "oldvis_config", "germ", "w", GERM_SUB + "o-sub", null, "r", "a");
        requestGerm(old, "Все");
        check("xHTTP добавлен", germ.inboundsOf("oldvis_config").equals(ids(2, 3, 5)));
        check("старый Reality без flow не тронут", germ.flowOf("oldvis_config", 5).isEmpty());
        check("bulkAdjust не вызывался", germ.writes.stream().noneMatch(w -> w.startsWith("bulkAdjust oldvis_config")));
    }

    /**
     * Случай с прода: страна хранилась в памяти и исчезала после любого завершённого запроса.
     * «Оба» на оставшейся клавиатуре уходило в Латвию и показывало конфиг без xHTTP.
     */
    private static void bothOnStaleKeyboard() throws Exception {
        U u = registered("stalekb");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        latv.put("stalekb_config", "uuid-l", "sub-l", u.id, 1, 8);
        germ.put("stalekb_config", "uuid-g", "sub-g", u.id, 2, 3, 5);
        dbConfig(u.id, "stalekb_config", "latv", "ws", LATV_SUB + "sub-l", null, "reality", "a");
        dbConfig(u.id, "stalekb_config", "germ", "ws", GERM_SUB + "sub-g", "xhttp", "reality", "a");

        u.say("/get_config");
        u.press("🇩🇪 Германия");
        u.press("✅ Принимаю условия");
        Msg typeKeyboard = u.last();
        String both = typeKeyboard.buttons.get("Все");

        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        expectLinks(u.last(), true, false, true);

        tg.click(u.id, u.name, typeKeyboard.id, typeKeyboard.text, both);
        settle();
        check("«Оба» показывает Германию", typeKeyboard.text.contains("Германия") && !typeKeyboard.text.contains("Латвия"));
        expectLinks(typeKeyboard, true, true, true);

        // То же для двойного нажатия: второй обработчик раньше уходил в Латвию и затирал ответ
        u.say("/get_config");
        u.press("🇩🇪 Германия");
        u.press("✅ Принимаю условия");
        Msg second = u.last();
        String bothAgain = second.buttons.get("Все");
        tg.click(u.id, u.name, second.id, second.text, bothAgain);
        tg.click(u.id, u.name, second.id, second.text, bothAgain);
        settle();
        check("двойное «Оба» показывает Германию", second.text.contains("Германия") && !second.text.contains("Латвия"));
        expectLinks(second, true, true, true);
    }

    private static void doubleTapEveryStep() throws Exception {
        U u = registered("tap2");
        for (String button : List.of("Получить конфиг", "🇩🇪 Германия", "✅ Принимаю условия", "Отмена")) {
            Msg m = findButton(u.id, button);
            check("есть кнопка «" + button + "»", m != null);
            if (m == null) return;
            String data = m.buttons.get(button);
            tg.click(u.id, u.name, m.id, m.text, data);
            tg.click(u.id, u.name, m.id, m.text, data);
            settle();
        }
        check("остался в рабочем меню", u.last().buttons.containsKey("Получить конфиг"));
        check("панели не тронуты", germ.clients.isEmpty() && latv.clients.isEmpty());
    }

    /** Одобренный старый пользователь нажимает «Оба»: должен получить все три протокола, без новых заявок. */
    private static void bothOnOld(String configEmail, Integer[] configInbounds, String dbName,
                                  boolean dbWs, boolean dbXhttp, boolean dbReality,
                                  Integer[] xhttpTwin, boolean keepsSub) throws Exception {
        U u = registered("x");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        String oldSub = GERM_SUB + "old-sub";
        if (configEmail != null) germ.put(configEmail, "uuid-c", "old-sub", 0, configInbounds);
        if (xhttpTwin != null) germ.put("x_xhttp", "uuid-x", "old-sub", 0, xhttpTwin);
        dbConfig(u.id, dbName, "germ", dbWs ? "stale-ws" : null, oldSub, dbXhttp ? "stale-xhttp" : null, dbReality ? "stale-reality" : null, "a");
        applyDataMigration();

        requestGerm(u, "Все");

        check("клиент x_config в WS+XHTTP+Reality", germ.inboundsOf("x_config").equals(ids(2, 3, 5)));
        Msg m = u.last();
        check("ответ содержит ссылки, а не заявку", m.text.contains("vless://"));
        expectLinks(m, true, true, true);
        check("ссылки из панели, не устаревшие", !m.text.contains("stale-"));
        check("новая заявка админам не ушла", notification(ADMIN_A, u.id) == null);
        check("имя в БД — x_config", "x_config".equals(sql("SELECT config_name FROM config WHERE tg_id=?", u.id)));
        check("в БД все ссылки", "1".equals(sql("SELECT count(*) FROM config WHERE tg_id=? AND vless_link LIKE 'vless:%' AND xhttp_link LIKE 'vless:%' AND reality_link LIKE 'vless:%'", u.id)));
        if (keepsSub) check("подписка не поменялась", m.text.contains(oldSub));
        if (xhttpTwin != null) check("старый _xhttp не тронут", germ.inboundsOf("x_xhttp").equals(new TreeSet<>(List.of(xhttpTwin))));
    }

    private static void latviaOldWs() throws Exception {
        U u = registered("x");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        latv.put("x_config", "uuid-c", "old-sub", 0, 1);
        dbConfig(u.id, "x_config", "latv", "stale-ws", LATV_SUB + "old-sub", null, null, "a");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        check("привязан Reality", latv.inboundsOf("x_config").equals(ids(1, 8)));
        check("новый Reality с Vision", FakePanel.VISION.equals(latv.flowOf("x_config", 8)));
        expectLinks(u.last(), true, false, true);
    }

    private static void latviaOldXhttpName() throws Exception {
        U u = registered("x");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        latv.put("x_config", "uuid-c", "old-sub", 0, 1, 8);
        dbConfig(u.id, "x_xhttp", "latv", null, LATV_SUB + "old-sub", null, null, "a");
        applyDataMigration();
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        expectLinks(u.last(), true, false, true);
        check("лишний клиент x_xhttp не создан", !latv.clients.containsKey("x_xhttp"));
    }

    private static void usernameChanged() throws Exception {
        U u = registered("newname");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        germ.put("oldname_config", "uuid-c", "old-sub", u.id, 3);
        dbConfig(u.id, "oldname_config", "germ", "stale-ws", GERM_SUB + "old-sub", null, null, "a");
        requestGerm(u, "Все");
        check("старый клиент дополнен", germ.inboundsOf("oldname_config").equals(ids(2, 3, 5)));
        check("новый клиент не создан", !germ.clients.containsKey("newname_config"));
    }

    private static void foreignInbounds() throws Exception {
        U u = registered("x");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        FakePanel.Client c = latv.put("x_config", "uuid-c", "old-sub", 0, 1, 4, 6, 8);
        dbConfig(u.id, "x_config", "latv", "stale-ws", LATV_SUB + "old-sub", null, null, "a");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        check("WS из настроенного inbound 1", u.last().text.contains(latv.link(c, 1)));
        check("Reality из настроенного inbound 8, а не 6", u.last().text.contains(latv.link(c, 8)));
    }

    private static void noUsername() throws Exception {
        U u = registered(null);
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        check("просит ввести имя", u.last().text.contains("Введи имя"));
        check("кнопка отмены", u.last().buttons.containsKey("Отмена"));
        u.say("/foo");
        check("команда в режиме ввода", u.last().text.contains("ожидается имя"));
        u.say("плохое имя");
        check("неверный формат", u.last().text.contains("Неверный формат"));
        u.say("1abc");
        check("имя с цифры", u.last().text.contains("Неверный формат"));
        u.say("vasya_config");
        check("суффикс не удваивается", latv.clients.containsKey("vasya_config"));
        check("заявка отправлена", lastWithOrNull(u.id, "ожидает") != null);
        Msg n = notification(ADMIN_A, u.id);
        check("админ уведомлён", n != null);
        check("в уведомлении не выдаёт имя за @username", n != null && !n.text.contains("@vasya"));
    }

    private static void noUsernameCancel() throws Exception {
        U u = registered(null);
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        u.press("Отмена");
        check("кнопка возвращает в меню", u.last().text.contains("Привет"));
        u.say("abc");
        check("режим ввода снят кнопкой", u.last().text.contains("не понял"));

        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        u.say("/cancel");
        check("/cancel возвращает в меню", u.last().text.contains("Привет"));
        u.say("abc");
        check("режим ввода снят /cancel", u.last().text.contains("не понял"));
        check("клиент не создан", latv.clients.isEmpty());

        // Команда в обход ввода не оставляет бота в режиме ввода
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        u.say("/get_config");
        check("/get_config во время ввода открывает выбор страны", u.last().buttons.containsKey("🇱🇻 Латвия"));
        u.say("abc");
        check("после /get_config текст — не имя конфига", latv.clients.isEmpty());

        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        u.say("/start");
        check("/start во время ввода открывает меню", u.last().text.contains("Привет"));
        u.say("abc");
        check("после /start текст — не имя конфига", latv.clients.isEmpty());
    }

    private static void nameTaken() throws Exception {
        U owner = registered("petr");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", owner.id);
        latv.put("petr_config", "uuid-owner", "owner-sub", owner.id, 1, 8);
        dbConfig(owner.id, "petr_config", "latv", "ws", LATV_SUB + "owner-sub", null, "reality", "a");
        int writesBefore = latv.writes.size();

        U thief = registered(null);
        thief.say("/get_config");
        thief.press("🇱🇻 Латвия");
        thief.say("petr");
        check("занятое имя отклонено", thief.last().text.contains("занято"));
        check("панель не тронута", latv.writes.size() == writesBefore);
        check("чужой конфиг не записан пользователю", "0".equals(sql("SELECT count(*) FROM config WHERE tg_id=?", thief.id)));
        check("админам заявка не ушла", notification(ADMIN_A, thief.id) == null);
        thief.say("thief");
        check("можно ввести другое имя", latv.clients.containsKey("thief_config"));

        // Тот же @username у другого человека (старый владелец его сменил)
        U namesake = new U(nextUserId++, "petr");
        namesake.say("/start");
        namesake.say("/get_config");
        namesake.press("🇱🇻 Латвия");
        check("при занятом username просит ввести имя", namesake.last().text.contains("занято"));
        check("у однофамильца нет заявки", notification(ADMIN_A, namesake.id) == null);

        // Клиент есть только на панели, но с чужим tgId
        latv.put("foreign_config", "uuid-f", "f-sub", 777777, 1);
        U other = registered("foreign");
        other.say("/get_config");
        other.press("🇱🇻 Латвия");
        check("клиент с чужим tgId не отдаётся", other.last().text.contains("занято"));
        check("чужой клиент не дополнен", latv.inboundsOf("foreign_config").equals(ids(1)));

        // Тот же email в другом регистре панель не создаст
        latv.put("CaseUser_config", "uuid-cu", "cu-sub", 0, 1);
        U caseUser = registered("caseuser");
        caseUser.say("/get_config");
        caseUser.press("🇱🇻 Латвия");
        check("другой регистр: просит другое имя", caseUser.last().text.contains("занято"));
    }

    private static void secondCountry() throws Exception {
        U u = registered("two");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        adminPress(ADMIN_A, notification(ADMIN_A, u.id), "Одобрить");

        requestGerm(u, "Все");
        check("Германия — снова заявка", u.last().text.contains("ожидает"));
        Msg n = notification(ADMIN_A, u.id);
        check("админу пришла новая заявка на Германию", n != null && n.text.contains("Германия"));

        u.press("Проверить статус");
        check("статус не отдаёт неодобренную Германию", !u.last().text.contains(GERM_SUB) && !u.last().text.contains("germ.test"));
        check("статус отдаёт одобренную Латвию", u.last().text.contains("latv.test"));
        check("статус говорит, что Германия ждёт", u.last().text.contains("Германия") && u.last().text.contains("ожидает"));
        check("кнопка статуса остаётся", u.last().buttons.containsKey("Проверить статус"));
        u.press("Проверить статус");

        if (n != null) adminPress(ADMIN_A, n, "Одобрить");
        Msg links = lastWith(u.id, "germ.test");
        check("после одобрения пришла Германия", links != null);
        check("вместе с Латвией", links != null && links.text.contains("latv.test"));
    }

    private static void repeatApproved() throws Exception {
        U u = registered("rep");
        requestGerm(u, "WebSocket");
        adminPress(ADMIN_A, notification(ADMIN_A, u.id), "Одобрить");
        int adminMessages = countAll(ADMIN_A);
        requestGerm(u, "WebSocket");
        expectLinks(u.last(), true, false, false);
        check("админов не трогаем", countAll(ADMIN_A) == adminMessages);
    }

    /** Разрешение выдаётся на страну: новые типы в одобренной стране админов не касаются. */
    private static void approvalIsPerCountry() throws Exception {
        U u = registered("percountry");
        requestGerm(u, "WebSocket");
        adminPress(ADMIN_A, notification(ADMIN_A, u.id), "Одобрить");
        int adminA = countAll(ADMIN_A);
        int adminB = countAll(ADMIN_B);

        requestGerm(u, "xHTTP");
        check("xHTTP привязан без заявки", germ.inboundsOf("percountry_config").equals(ids(2, 3)));
        expectLinks(u.last(), true, true, false);

        requestGerm(u, "Все");
        check("«Все» добавил Reality", germ.inboundsOf("percountry_config").equals(ids(2, 3, 5)));
        expectLinks(u.last(), true, true, true);

        requestGerm(u, "WebSocket");
        check("выбор одного протокола не отвязывает остальные", germ.inboundsOf("percountry_config").equals(ids(2, 3, 5)));

        // Протокол, появившийся уже после одобрения (так в своё время добавился Reality)
        germ.inboundsOf("percountry_config").remove(5);
        requestGerm(u, "Reality");
        check("новый протокол привязан без заявки", germ.inboundsOf("percountry_config").contains(5));
        check("его ссылка сразу в ответе", u.last().text.contains("Reality конфиг"));

        check("админам ничего не пришло", countAll(ADMIN_A) == adminA && countAll(ADMIN_B) == adminB);
        check("открытых заявок нет", notification(ADMIN_A, u.id) == null);

        // Другая страна — отдельное разрешение, и уже одобренной оно не мешает
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        check("новая страна — заявка", u.last().text.contains("ожидает"));
        Msg latvia = notification(ADMIN_A, u.id);
        check("админ видит заявку на Латвию", latvia != null && latvia.text.contains("Латвия"));
        int withLatvia = countAll(ADMIN_A);
        requestGerm(u, "Все");
        check("одобренная страна работает, пока другая ждёт", u.last().text.contains("germ.test"));
        check("это не создало новых заявок", countAll(ADMIN_A) == withLatvia);
    }

    private static void repeatPending() throws Exception {
        U u = registered("pend");
        requestGerm(u, "WebSocket");
        int adminMessages = countAll(ADMIN_A);
        requestGerm(u, "Все");
        check("панель дополнена", germ.inboundsOf("pend_config").equals(ids(2, 3, 5)));
        check("ссылок до одобрения нет", !u.last().text.contains("vless://"));
        check("у админа нет второго уведомления", countAll(ADMIN_A) == adminMessages);
        Msg n = notification(ADMIN_A, u.id);
        adminPress(ADMIN_A, n, "Одобрить");
        Msg links = lastWith(u.id, "vless://");
        check("одобрение одно на обе заявки", links != null);
        if (links != null) expectLinks(links, true, true, true);
    }

    private static void doubleClick() throws Exception {
        U u = registered("dbl");
        germ.addDelayMs = 300;
        u.say("/get_config");
        u.press("🇩🇪 Германия");
        u.press("✅ Принимаю условия");
        Msg m = u.last();
        String data = m.buttons.get("Все");
        tg.click(u.id, u.name, m.id, m.text, data);
        tg.click(u.id, u.name, m.id, m.text, data);
        settle();
        Thread.sleep(800);
        settle();
        check("один клиент в трёх inbound'ах", germ.inboundsOf("dbl_config").equals(ids(2, 3, 5)));
        check("второе нажатие не ушло в Латвию", !latv.clients.containsKey("dbl_config"));
        check("пользователь не видит ошибки", !u.last().text.contains("Ошибка"));
        check("одно уведомление админу", countWithExact(ADMIN_A, "approve:" + u.id) == 1);
        check("одна запись в БД", "1".equals(sql("SELECT count(*) FROM config WHERE tg_id=?", u.id)));
    }

    private static void panelDown() throws Exception {
        U u = registered("down");
        germ.failStatus = 500;
        requestGerm(u, "Все");
        check("показана ошибка", u.last().text.contains("Ошибка"));
        check("внутренности панели не утекли", !u.last().text.contains("secret-panel-host") && !u.last().text.contains("body="));
        check("есть кнопка повторить", u.last().buttons.containsKey("Получить конфиг"));
        check("в БД пусто", "0".equals(sql("SELECT count(*) FROM config WHERE tg_id=?", u.id)));
        check("админов не беспокоили", notification(ADMIN_A, u.id) == null);
        germ.failStatus = 0;
        requestGerm(u, "Все");
        check("после восстановления заявка прошла", u.last().text.contains("ожидает"));
        check("клиент создан", germ.inboundsOf("down_config").equals(ids(2, 3, 5)));
    }

    private static void rejectFlow() throws Exception {
        U u = registered("rej");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg a = notification(ADMIN_A, u.id);
        Msg b = notification(ADMIN_B, u.id);
        String staleApprove = b.buttons.get("Одобрить");
        adminPress(ADMIN_A, a, "Отклонить");
        check("нажавшему видно «отклон»", a.text.contains("отклон") && a.buttons.isEmpty());
        check("второму видно «отклон»", b.text.contains("отклон") && b.buttons.isEmpty());
        check("пользователю сообщили", lastWith(u.id, "отклон") != null);
        check("ссылок нет", countWith(u.id, "vless://") == 0);

        u.press("Проверить статус");
        check("статус: заявка отклонена", u.last().text.contains("отклон"));
        check("статус: можно подать снова", u.last().buttons.containsKey("Получить конфиг"));

        String q = tg.click(ADMIN_B, "admin_" + ADMIN_B, b.id, b.text, staleApprove);
        settle();
        check("устаревшее «Одобрить» не сработало", countWith(u.id, "vless://") == 0);
        check("всплывашка о решении", tg.answers.getOrDefault(q, "").toLowerCase().contains("уже"));

        u.press("Получить конфиг");
        u.press("🇱🇻 Латвия");
        check("новая заявка", u.last().text.contains("ожидает"));
        Msg a2 = notification(ADMIN_A, u.id);
        check("админ снова уведомлён", a2 != null);
        if (a2 != null) adminPress(ADMIN_A, a2, "Одобрить");
        check("после одобрения ссылки пришли", lastWith(u.id, "vless://") != null);
    }

    private static void approveAfterDelete() throws Exception {
        U u = registered("gone");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg a = notification(ADMIN_A, u.id);
        adminDelete(ADMIN_B, "gone");
        adminPress(ADMIN_A, a, "Одобрить");
        check("ссылок нет", countWith(u.id, "vless://") == 0);
        check("админ не видит «отправлен»", !a.text.contains("отправлен"));
        check("кнопки убраны", a.buttons.isEmpty());
        exitAdmin(ADMIN_B);
        u.press("Проверить статус");
        check("пользователю не «ожидает» вечно", !u.last().text.contains("ожидает"));
        check("можно подать заново", u.last().buttons.containsKey("Получить конфиг"));
    }

    private static void simultaneousApprove() throws Exception {
        for (int i = 0; i < 5; i++) {
            U u = registered("race" + i);
            u.say("/get_config");
            u.press("🇱🇻 Латвия");
            Msg a = notification(ADMIN_A, u.id);
            Msg b = notification(ADMIN_B, u.id);
            String da = a.buttons.get("Одобрить");
            String db = b.buttons.get("Одобрить");
            tg.click(ADMIN_A, "admin_" + ADMIN_A, a.id, a.text, da);
            tg.click(ADMIN_B, "admin_" + ADMIN_B, b.id, b.text, db);
            settle();
            check("одно одобрение — одна отправка", countWith(u.id, "vless://") == 1);
            check("у первого админа кнопок нет", a.buttons.isEmpty());
            check("у второго админа кнопок нет", b.buttons.isEmpty());
        }
    }

    private static void approveVsReject() throws Exception {
        U u = registered("conflict");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg a = notification(ADMIN_A, u.id);
        Msg b = notification(ADMIN_B, u.id);
        String reject = b.buttons.get("Отклонить");
        adminPress(ADMIN_A, a, "Одобрить");
        int before = countWith(u.id, "vless://");
        String q = tg.click(ADMIN_B, "admin_" + ADMIN_B, b.id, b.text, reject);
        settle();
        check("одобренное нельзя отклонить задним числом", "a".equals(sql("SELECT status FROM config WHERE tg_id=?", u.id)));
        check("пользователю не пришёл отказ", countWith(u.id, "отклон") == 0);
        check("ссылки не задублированы", countWith(u.id, "vless://") == before);
        check("всплывашка второму", tg.answers.getOrDefault(q, "").toLowerCase().contains("уже"));
    }

    private static void legacyButtons() throws Exception {
        U u = registered("legacy1");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg a = notification(ADMIN_A, u.id);
        tg.click(ADMIN_A, "admin_" + ADMIN_A, a.id, a.text, "approve:" + u.id);
        settle();
        check("кнопка без страны одобряет заявку", countWith(u.id, "vless://") == 1);

        U w = registered("legacy2");
        w.say("/get_config");
        w.press("🇩🇪 Германия");
        w.press("✅ Принимаю условия");
        Msg m = w.last();
        tg.click(w.id, w.name, m.id, m.text, "config_type:both");
        settle();
        check("тип без страны: снова выбор страны", w.last().buttons.containsKey("🇩🇪 Германия"));
        check("в чужой стране ничего не создано", !latv.clients.containsKey("legacy2_config"));

        tg.click(w.id, w.name, m.id, m.text, "config_type:both:xx");
        settle();
        check("неизвестная страна: снова выбор страны", w.last().buttons.containsKey("🇵🇱 Польша"));
        check("неизвестная страна ничего не создала", !latv.clients.containsKey("legacy2_config")
                && !germ.clients.containsKey("legacy2_config") && !pol.clients.containsKey("legacy2_config"));
    }

    private static void userBlockedBot() throws Exception {
        U u = registered("blocker");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg a = notification(ADMIN_A, u.id);
        Msg b = notification(ADMIN_B, u.id);
        tg.blocked.add(u.id);
        adminPress(ADMIN_A, a, "Одобрить");
        expectedApiErrors.add("bot was blocked");
        check("админ видит, что не доставлено", a.text.contains("не удалось"));
        check("кнопки убраны у нажавшего", a.buttons.isEmpty());
        check("второму админу тоже видно", b.text.contains("одобрен") && b.buttons.isEmpty());
        check("статус сохранён", "a".equals(sql("SELECT status FROM config WHERE tg_id=?", u.id)));
    }

    private static void adminUnreachable() throws Exception {
        tg.unknownChats.add(ADMIN_A);
        expectedApiErrors.add("chat not found");
        U u = registered("oneadmin");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        check("второй админ уведомлён", notification(ADMIN_B, u.id) != null);
        check("пользователь видит заявку, не ошибку", u.last().text.contains("ожидает"));
        check("заявка осталась рабочей", "w".equals(sql("SELECT status FROM config WHERE tg_id=?", u.id)));
    }

    private static void restartKeepsCountry() throws Exception {
        U u = registered("restart");
        u.say("/get_config");
        u.press("🇩🇪 Германия");
        u.press("✅ Принимаю условия");
        restartBot();
        u.press("Все");
        check("конфиг создан в Германии", germ.inboundsOf("restart_config").equals(ids(2, 3, 5)));
        check("в Латвии ничего", !latv.clients.containsKey("restart_config"));

        U p = registered("restartp");
        p.say("/get_config");
        p.press("🇵🇱 Польша");
        restartBot();
        p.press("Все");
        check("конфиг создан в Польше", pol.inboundsOf("restartp_config").equals(ids(1, 2, 3)));
        check("в других странах ничего", !latv.clients.containsKey("restartp_config") && !germ.clients.containsKey("restartp_config"));
    }

    private static void restartDuringApproval() throws Exception {
        U u = registered("restart2");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg a = notification(ADMIN_A, u.id);
        Msg b = notification(ADMIN_B, u.id);
        String staleB = b.buttons.get("Одобрить");
        restartBot();
        adminPress(ADMIN_A, a, "Одобрить");
        check("ссылки отправлены", lastWith(u.id, "vless://") != null);
        check("нажавшему видно «одобрен»", a.text.contains("одобрен") && a.buttons.isEmpty());
        int before = countWith(u.id, "vless://");
        String q = tg.click(ADMIN_B, "admin_" + ADMIN_B, b.id, b.text, staleB);
        settle();
        check("второй админ не дублирует отправку", countWith(u.id, "vless://") == before);
        check("второму всплывашка «уже»", tg.answers.getOrDefault(q, "").toLowerCase().contains("уже"));
        check("сообщение второго обновилось", b.text.contains("одобрен") && b.buttons.isEmpty());
    }

    private static void adminPanel() throws Exception {
        U admin = new U(ADMIN_A, "admin_" + ADMIN_A);
        admin.say("/admin");
        check("панель открыта", admin.last().buttons.containsKey("Удалить конфиг"));
        admin.press("Список пользователей");
        check("список в том же сообщении", admin.last().text.contains("Панель администратора"));
        admin.press("Список пользователей");
        admin.press("Синхронизировать с панелью");
        check("результат синка", admin.last().text.contains("Синхронизация завершена"));
        admin.press("Синхронизировать с панелью");
        admin.say("/users");
        admin.say("/syncPanel");
        check("/syncPanel отвечает", admin.last().text.contains("Синхронизация завершена"));
        admin.press("Выйти из режима");
        check("выход из режима", lastWithOrNull(ADMIN_A, "вышли") != null);
        admin.say("/admin");
        admin.say("/exAdmin");
        check("/exAdmin", admin.last().text.contains("вышли"));
    }

    private static void nonAdmin() throws Exception {
        U u = registered("hacker");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg own = u.last();
        u.say("/admin");
        check("/admin недоступен", !u.last().buttons.containsKey("Удалить конфиг"));
        u.say("/users");
        check("/users недоступен", !u.last().text.contains("Пользователи в БД"));
        tg.click(u.id, u.name, own.id, own.text, "approve:" + u.id);
        settle();
        check("сам себя не одобрит", countWith(u.id, "vless://") == 0);
        tg.click(u.id, u.name, own.id, own.text, "approve:" + u.id + ":latv");
        settle();
        check("и с указанием страны", countWith(u.id, "vless://") == 0);
        check("статус не поменялся", "w".equals(sql("SELECT status FROM config WHERE tg_id=?", u.id)));
        tg.click(u.id, u.name, own.id, own.text, "admin:users");
        settle();
        check("админская кнопка не работает", !own.text.contains("Пользователи в БД"));
        tg.sentQueries.clear(); // без фильтра бот на такие нажатия и не должен отвечать
    }

    private static void sync() throws Exception {
        U pending = registered("waits");
        pending.say("/get_config");
        pending.press("🇱🇻 Латвия");

        // Клиент только на панели — появляется в БД одобренным
        long panelOnly = nextUserId++;
        latv.put("fromPanel_config", "uuid-p", "p-sub", panelOnly, 1, 8);
        // Старый клиент без tgId — ссылки обновляются по имени
        U old = registered("legacy");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", old.id);
        germ.put("legacy_config", "uuid-l", "l-sub", 0, 2, 3, 5);
        dbConfig(old.id, "legacy_config", "germ", null, GERM_SUB + "l-sub", "stale-xhttp", null, "a");
        // Старый _xhttp с tgId не должен перетирать основной конфиг
        U twin = registered("twin");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", twin.id);
        germ.put("twin_config", "uuid-t", "t-sub", twin.id, 2, 3, 5);
        germ.put("twin_xhttp", "uuid-tx", "t-sub", twin.id, 2);
        dbConfig(twin.id, "twin_config", "germ", "w", GERM_SUB + "t-sub", "x", "r", "a");
        // В БД имя x_xhttp, на панели есть x_config без tgId
        U named = registered("named");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", named.id);
        germ.put("named_config", "uuid-n", "n-sub", 0, 2, 3, 5);
        germ.put("named_xhttp", "uuid-nx", "n-sub", 0, 2);
        dbConfig(named.id, "named_xhttp", "germ", null, GERM_SUB + "n-sub", "stale-xhttp", null, "a");
        applyDataMigration();
        // Клиент только на польской панели
        long polOnly = nextUserId++;
        pol.put("polOnly_config", "uuid-q", "q-sub", polOnly, 1, 2, 3);

        U admin = new U(ADMIN_A, "admin_" + ADMIN_A);
        admin.say("/syncPanel");
        check("синк завершён", admin.last().text.contains("Синхронизация завершена"));
        check("синк прошёл по всем странам", admin.last().text.contains("latv: Синхронизация")
                && admin.last().text.contains("germ: Синхронизация") && admin.last().text.contains("pol: Синхронизация"));
        check("клиент Польши появился одобренным", "a".equals(sql("SELECT status FROM config WHERE tg_id=? AND country='pol'", polOnly)));
        check("у клиента Польши все ссылки", "1".equals(sql("SELECT count(*) FROM config WHERE tg_id=? AND sub_link=? AND vless_link LIKE 'vless:%' AND xhttp_link LIKE 'vless:%' AND reality_link LIKE 'vless:%'", polOnly, POL_SUB + "q-sub")));

        check("заявка не одобрена синком", "w".equals(sql("SELECT status FROM config WHERE tg_id=?", pending.id)));
        pending.press("Проверить статус");
        check("ссылок ожидающему нет", !pending.last().text.contains("vless://"));
        check("кнопки заявки у админа живы", notification(ADMIN_A, pending.id) != null);

        check("клиент с панели появился одобренным", "a".equals(sql("SELECT status FROM config WHERE tg_id=?", panelOnly)));
        check("ссылки обновлены по имени", "1".equals(sql("SELECT count(*) FROM config WHERE tg_id=? AND reality_link LIKE 'vless:%' AND vless_link LIKE 'vless:%' AND xhttp_link LIKE 'vless:%'", old.id)));
        check("пара _xhttp не перетёрла имя", "twin_config".equals(sql("SELECT config_name FROM config WHERE tg_id=?", twin.id)));
        check("пара _xhttp не перетёрла ссылки", "1".equals(sql("SELECT count(*) FROM config WHERE tg_id=? AND vless_link LIKE 'vless:%' AND reality_link LIKE 'vless:%'", twin.id)));
        check("имя x_xhttp исправлено по паре", "named_config".equals(sql("SELECT config_name FROM config WHERE tg_id=?", named.id)));
        check("ссылки по паре обновлены", "1".equals(sql("SELECT count(*) FROM config WHERE tg_id=? AND reality_link LIKE 'vless:%'", named.id)));
    }

    private static void deleteBasics() throws Exception {
        U admin = new U(ADMIN_A, "admin_" + ADMIN_A);
        U u = registered("del1");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        adminPress(ADMIN_A, notification(ADMIN_A, u.id), "Одобрить");

        admin.say("/admin");
        admin.press("Удалить конфиг");
        check("просит имя", admin.last().text.contains("Введи имя"));
        admin.press("Отмена");
        check("отмена возвращает панель", admin.last().buttons.containsKey("Удалить конфиг"));
        check("отмена ничего не удалила", latv.clients.containsKey("del1_config"));

        admin.press("Удалить конфиг");
        admin.say("nosuch");
        check("неизвестное имя", admin.last().text.contains("не найден"));

        admin.press("Удалить конфиг");
        admin.say("del1_config");
        check("удалён с панели", !latv.clients.containsKey("del1_config"));
        check("удалён из БД", "0".equals(sql("SELECT count(*) FROM config WHERE tg_id=?", u.id)));
        check("статус сброшен", "w".equals(sql("SELECT wait_accept FROM tg_user WHERE id=?", u.id)));
        check("флаг конфига сброшен", "f".equals(sql("SELECT has_config FROM tg_user WHERE id=?", u.id)));
        admin.press("Список пользователей");
        check("в списке без конфига", admin.last().text.contains("@del1") && admin.last().text.contains("без конфига"));
        exitAdmin(ADMIN_A);

        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        check("после удаления — новая заявка", u.last().text.contains("ожидает"));
        check("без одобрения ссылок нет", !u.last().text.contains("vless://"));
    }

    private static void deleteBothCountries() throws Exception {
        U u = registered("del2");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        latv.put("del2_config", "uuid-a", "sub-a", u.id, 1, 8);
        germ.put("del2_config", "uuid-b", "sub-b", u.id, 2, 3, 5);
        germ.put("del2_xhttp", "uuid-c", "sub-b", 0, 2);
        pol.put("del2_config", "uuid-d", "sub-d", u.id, 1, 2, 3);
        dbConfig(u.id, "del2_config", "latv", "w", LATV_SUB + "sub-a", null, "r", "a");
        dbConfig(u.id, "del2_config", "germ", "w", GERM_SUB + "sub-b", "x", "r", "a");
        dbConfig(u.id, "del2_config", "pol", "w", POL_SUB + "sub-d", "x", "r", "a");

        adminDelete(ADMIN_A, "del2");
        Msg m = new U(ADMIN_A, null).last();
        check("нет ошибки удаления", !m.text.contains("Ошибка"));
        check("удалён в Латвии", !latv.clients.containsKey("del2_config"));
        check("удалён в Германии", !germ.clients.containsKey("del2_config"));
        check("удалён в Польше", !pol.clients.containsKey("del2_config"));
        check("старый _xhttp тоже отозван", !germ.clients.containsKey("del2_xhttp"));
        check("в БД пусто", "0".equals(sql("SELECT count(*) FROM config WHERE tg_id=?", u.id)));
        exitAdmin(ADMIN_A);
    }

    private static void deletePanelDown() throws Exception {
        U u = registered("del3");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        latv.failStatus = 500;
        adminDelete(ADMIN_A, "del3");
        Msg m = new U(ADMIN_A, null).last();
        check("админ видит, что с панели не удалено", m.text.contains("не удалось"));
        check("внутренности панели не показаны", !m.text.contains("secret-panel-host"));
        latv.failStatus = 0;
        exitAdmin(ADMIN_A);
    }

    private static void markdownUsername() throws Exception {
        U u = registered("D_user_x_");
        u.say("/get_config");
        u.press("🇱🇻 Латвия");
        Msg n = notification(ADMIN_A, u.id);
        check("уведомление дошло", n != null);
        if (n != null) adminPress(ADMIN_A, n, "Одобрить");
        Msg links = lastWith(u.id, "vless://");
        check("ссылки дошли", links != null);
        check("второму админу статус дошёл", notificationById(ADMIN_B, u.id).text.contains("одобрен"));
    }

    private static void longUserList() throws Exception {
        for (int i = 0; i < 150; i++) {
            sqlExec("INSERT INTO tg_user(id, tg_name, has_config, wait_accept) VALUES (?, ?, false, 'w')", 500000L + i, "long_username_number_" + i);
        }
        U admin = new U(ADMIN_A, "admin_" + ADMIN_A);
        admin.say("/users");
        check("/users не упал на длине", admin.last().text.contains("Пользователи в БД"));
        admin.say("/syncPanel");
        check("/syncPanel не упал на длине", admin.last().text.contains("Синхронизация завершена"));
        admin.say("/admin");
        admin.press("Список пользователей");
        check("кнопка не упала на длине", admin.last().buttons.containsKey("Удалить конфиг"));
        exitAdmin(ADMIN_A);
    }

    private static void staleCallback() throws Exception {
        U u = registered("stale");
        u.say("/get_config");
        tg.queriesTooOld = true;
        expectedApiErrors.add("query is too old");
        u.press("🇱🇻 Латвия");
        tg.queriesTooOld = false;
        check("обработка дошла до конца", latv.clients.containsKey("stale_config"));
        check("пользователь видит результат", u.last().text.contains("ожидает"));
        tg.sentQueries.clear();
    }

    private static void statusRefreshesLinks() throws Exception {
        U u = registered("refresh");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", u.id);
        germ.put("refresh_config", "uuid-r", "r-sub", 0, 2, 3, 5);
        dbConfig(u.id, "refresh_config", "germ", null, GERM_SUB + "r-sub", "stale-xhttp", null, "a");
        Msg m = u.last();
        tg.click(u.id, u.name, m.id, m.text, "user:check_status");
        settle();
        expectLinks(u.last(), true, true, true);
        check("устаревшая ссылка не показана", !u.last().text.contains("stale-"));

        // Панель недоступна — показываем то, что есть в БД, а не ошибку
        germ.failStatus = 500;
        tg.click(u.id, u.name, m.id, m.text, "user:check_status");
        settle();
        check("при сбое панели ссылки из БД", u.last().text.contains("vless://"));
    }

    private static void dataMigration() throws Exception {
        U approved = registered("mig1");
        sqlExec("UPDATE tg_user SET wait_accept='a', has_config=true WHERE id=?", approved.id);
        dbConfig(approved.id, "mig1_xhttp", "germ", null, GERM_SUB + "s1", "x", null, "w");
        dbConfig(approved.id, "mig1_config", "latv", "w", LATV_SUB + "s1b", null, null, "w");
        U waiting = registered("mig2");
        dbConfig(waiting.id, "mig2_config", "latv", "w", LATV_SUB + "s2", null, null, "w");

        applyDataMigration();
        check("статус одобренного перенесён", "2".equals(sql("SELECT count(*) FROM config WHERE tg_id=? AND status='a'", approved.id)));
        check("ожидающий остался ждать", "w".equals(sql("SELECT status FROM config WHERE tg_id=?", waiting.id)));
        check("имя _xhttp исправлено", "mig1_config".equals(sql("SELECT config_name FROM config WHERE tg_id=? AND country='germ'", approved.id)));
    }

    private static void panelHangs() throws Exception {
        U u = registered("hang");
        U other = registered("alive");
        germ.hang = new CountDownLatch(1);
        u.say("/get_config");
        u.press("🇩🇪 Германия");
        u.press("✅ Принимаю условия");
        Msg m = u.last();
        tg.click(u.id, u.name, m.id, m.text, m.buttons.get("Все"));
        tg.click(u.id, u.name, m.id, m.text, m.buttons.get("Все"));
        Thread.sleep(500);
        long t0 = System.currentTimeMillis();
        tg.say(other.id, other.name, "/help");
        boolean answered = false;
        while (System.currentTimeMillis() - t0 < 5000 && !answered) {
            Thread.sleep(50);
            Msg last = other.last();
            answered = last != null && last.text.contains("/get_config");
        }
        check("другие пользователи получают ответ", answered);

        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline && !m.text.contains("Ошибка")) {
            Thread.sleep(200);
        }
        check("зависшая панель даёт ошибку за конечное время", m.text.contains("Ошибка"));
        germ.reset();
        settle();
    }

    // ── DSL ──────────────────────────────────────────────────────────────────

    private static final class U {
        final long id;
        final String name;

        U(long id, String name) {
            this.id = id;
            this.name = name;
        }

        void say(String text) throws Exception {
            tg.say(id, name, text);
            settle();
        }

        void press(String button) throws Exception {
            Msg m = findButton(id, button);
            if (m == null) {
                fail("нет кнопки «" + button + "», последнее сообщение: " + shorten(last()));
                return;
            }
            tg.click(id, name, m.id, m.text, m.buttons.get(button));
            settle();
        }

        Msg last() {
            synchronized (tg.history) {
                for (int i = tg.history.size() - 1; i >= 0; i--) {
                    Msg m = tg.history.get(i);
                    if (m.chatId == id && !m.deleted) return m;
                }
            }
            return null;
        }
    }

    private static U user(String name) {
        return new U(nextUserId++, name);
    }

    private static U registered(String name) throws Exception {
        U u = user(name);
        u.say("/start");
        return u;
    }

    private static void requestGerm(U u, String type) throws Exception {
        u.say("/get_config");
        u.press("🇩🇪 Германия");
        u.press("✅ Принимаю условия");
        u.press(type);
    }

    private static void requestPol(U u, String type) throws Exception {
        u.say("/get_config");
        u.press("🇵🇱 Польша");
        u.press(type);
    }

    private static void adminPress(long admin, Msg notification, String button) throws Exception {
        if (notification == null) {
            fail("нет уведомления для «" + button + "»");
            return;
        }
        tg.click(admin, "admin_" + admin, notification.id, notification.text, notification.buttons.get(button));
        settle();
    }

    private static void adminDelete(long admin, String name) throws Exception {
        U a = new U(admin, "admin_" + admin);
        a.say("/admin");
        a.press("Удалить конфиг");
        a.say(name);
    }

    private static void exitAdmin(long admin) throws Exception {
        new U(admin, "admin_" + admin).say("/exAdmin");
    }

    private static Msg findButton(long chatId, String button) {
        synchronized (tg.history) {
            for (int i = tg.history.size() - 1; i >= 0; i--) {
                Msg m = tg.history.get(i);
                if (m.chatId == chatId && !m.deleted && m.buttons.containsKey(button)) return m;
            }
        }
        return null;
    }

    /** Уведомление админу с живыми кнопками по заявке этого пользователя. */
    private static Msg notification(long admin, long userId) {
        synchronized (tg.history) {
            for (int i = tg.history.size() - 1; i >= 0; i--) {
                Msg m = tg.history.get(i);
                if (m.chatId != admin) continue;
                for (String data : m.buttons.values()) {
                    if (data.equals("approve:" + userId) || data.startsWith("approve:" + userId + ":")) return m;
                }
            }
        }
        return null;
    }

    /** Любое уведомление админу об этом пользователе, в том числе уже обработанное. */
    private static Msg notificationById(long admin, long userId) {
        synchronized (tg.history) {
            for (int i = tg.history.size() - 1; i >= 0; i--) {
                Msg m = tg.history.get(i);
                if (m.chatId == admin && m.text != null && m.text.contains(String.valueOf(userId))) return m;
            }
        }
        Msg none = new Msg(admin, -1);
        none.text = "";
        return none;
    }

    private static Msg lastWith(long chatId, String needle) {
        Msg m = lastWithOrNull(chatId, needle);
        if (m == null) report.println("    (нет сообщения с «" + needle + "»)");
        return m;
    }

    private static Msg lastWithOrNull(long chatId, String needle) {
        synchronized (tg.history) {
            for (int i = tg.history.size() - 1; i >= 0; i--) {
                Msg m = tg.history.get(i);
                if (m.chatId == chatId && !m.deleted && m.text != null && m.text.contains(needle)) return m;
            }
        }
        return null;
    }

    private static int countWith(long chatId, String needle) {
        int n = 0;
        synchronized (tg.history) {
            for (Msg m : tg.history) {
                if (m.chatId == chatId && m.text != null && m.text.contains(needle)) n++;
            }
        }
        return n;
    }

    private static int countAll(long chatId) {
        int n = 0;
        synchronized (tg.history) {
            for (Msg m : tg.history) {
                if (m.chatId == chatId) n++;
            }
        }
        return n;
    }

    private static int countWithExact(long admin, String dataPrefix) {
        int n = 0;
        synchronized (tg.history) {
            for (Msg m : tg.history) {
                if (m.chatId != admin) continue;
                for (String data : m.buttons.values()) {
                    if (data.equals(dataPrefix) || data.startsWith(dataPrefix + ":")) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    private static void expectLinks(Msg m, boolean ws, boolean xhttp, boolean reality) {
        if (m == null) {
            fail("нет сообщения со ссылками");
            return;
        }
        check("подписка в ответе", m.text.contains("Подписка"));
        check("WebSocket " + (ws ? "есть" : "нет"), m.text.contains("WebSocket конфиг") == ws);
        check("xHTTP " + (xhttp ? "есть" : "нет"), m.text.contains("xHTTP конфиг") == xhttp);
        check("Reality " + (reality ? "есть" : "нет"), m.text.contains("Reality конфиг") == reality);
    }

    private static Set<Integer> ids(Integer... ids) {
        return new TreeSet<>(List.of(ids));
    }

    private static void settle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (tg.idle() && Activity.idleMillis() >= 300) return;
            Thread.sleep(10);
        }
        fail("бот не закончил обработку за 20 секунд");
    }

    private static void reset() throws Exception {
        tg.reset();
        latv.reset();
        germ.reset();
        pol.reset();
        expectedApiErrors.clear();
        crashes.clear();
        sqlExec("TRUNCATE config, tg_user");
    }

    private static void scenario(String name, Body body) {
        current = name;
        int failedBefore = failed.size();
        try {
            reset();
            body.run();
            settle();
            synchronized (tg.apiErrors) {
                for (String err : tg.apiErrors) {
                    if (!err.contains("message is not modified") && expectedApiErrors.stream().noneMatch(err::contains)) {
                        fail("ошибка Bot API — " + err);
                    }
                }
            }
            for (String crash : new ArrayList<>(crashes)) {
                fail("обработчик упал — " + crash);
            }
            for (String q : tg.sentQueries) {
                if (!tg.answers.containsKey(q)) fail("кнопка «крутится»: нет answerCallbackQuery на " + q);
            }
        } catch (Throwable t) {
            fail("исключение " + t);
        }
        report.println((failed.size() == failedBefore ? "✔ " : "✘ ") + name);
    }

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else fail(what);
    }

    private static void fail(String what) {
        failed.add(current + " — " + what);
    }

    private static String shorten(Msg m) {
        if (m == null || m.text == null) return "<нет>";
        return m.text.length() > 80 ? m.text.substring(0, 80) + "…" : m.text;
    }

    // ── БД ───────────────────────────────────────────────────────────────────

    private static void dbConfig(long tgId, String name, String country, String ws, String sub, String xhttp, String reality, String status) throws Exception {
        sqlExec("INSERT INTO config(tg_id, config_name, vless_link, sub_link, xhttp_link, reality_link, country, status) VALUES (?,?,?,?,?,?,?,?)",
                tgId, name, ws, sub, xhttp, reality, country, status);
    }

    private static void bind(PreparedStatement ps, Object... args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) ps.setNull(i + 1, java.sql.Types.VARCHAR);
            else ps.setObject(i + 1, args[i]);
        }
    }

    private static void sqlExec(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            ps.execute();
        }
    }

    private static String sql(String sql, Object... args) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Тот же SQL, что выполнит Liquibase при деплое на накопленных данных. */
    private static void applyDataMigration() throws Exception {
        for (String stmt : changesetStatements("db/changeset/normalize-config-names-and-status.sql")) {
            sqlExec(stmt);
        }
    }

    private static List<String> changesetStatements(String resource) throws Exception {
        List<String> result = new ArrayList<>();
        try (InputStream in = ScenarioRunner.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("нет ресурса " + resource);
            StringBuilder sql = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().startsWith("--")) sql.append(line).append('\n');
            }
            for (String stmt : sql.toString().split(";")) {
                if (!stmt.isBlank()) result.add(stmt);
            }
        }
        return result;
    }
}
