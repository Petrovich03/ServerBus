package org.example

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import net.md_5.bungee.api.plugin.Plugin
import org.jsoup.Jsoup
import java.io.File
import java.sql.DriverManager
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

class MyPlugin : Plugin() {
    private var ktorServer: ApplicationEngine? = null
    private val ktorScope = CoroutineScope(Dispatchers.Default)
    private val serverLogger: Logger = Logger.getLogger("ServerLogger")
    private val updateCheckUrl = "https://zippybus.com/by/grodno/schedule-changes"
    private val scheduleChangesUrl = "https://zippybus.com/by/grodno/schedule-changes"
    private val lastUpdateFile: File by lazy { File(dataFolder, "database/last_update.txt") }

    override fun onEnable() {
        val dbFolder = File(dataFolder, "database")
        if (!dbFolder.exists()) {
            dbFolder.mkdirs()
            logger.info("Создана папка для базы данных: ${dbFolder.absolutePath}")
        }

        val handler = FileHandler("server.log", true)
        handler.formatter = SimpleFormatter()
        serverLogger.addHandler(handler)
        serverLogger.level = Level.ALL

        ktorScope.launch {
            ktorServer = embeddedServer(Netty, host = "0.0.0.0", port = 25594) {
                routing {
                    get("/download-db") {
                        val clientIP = call.request.origin.remoteHost ?: "IP"
                        val requestTime = java.time.LocalDateTime.now()
                        serverLogger.info("Запрос с IP: $clientIP at $requestTime")

                        val dbFile = File(dbFolder, "grodno_bus.db")
                        if (dbFile.exists()) {
                            call.respondFile(dbFile)
                        } else {
                            call.respondText("БД не найдена", status = io.ktor.http.HttpStatusCode.NotFound)
                        }
                    }
                }
                launch {
                    while (isActive) {
                        checkAndUpdateIfNeeded()
                        delay(60 * 60 * 1000L)
                    }
                }
            }.start(wait = false)
            serverLogger.info("Ktor server start")
        }
    }

    override fun onDisable() {
        ktorScope.cancel()
        ktorServer?.stop(1000, 2000)
    }

    private suspend fun checkAndUpdateIfNeeded() {
        try {
            serverLogger.info("Проверка даты обновления расписания")
            val newDate = fetchUpdateDate()
            val lastDate = readLastUpdateDate()

            serverLogger.info("Текущая дата обновления: $newDate, последняя сохранённая: $lastDate")
            if (newDate != null && newDate != lastDate) {
                serverLogger.info("Обнаружено обновление расписания. Запуск парсинга.")
                parseAndSaveToDb()
                saveLastUpdateDate(newDate)
                serverLogger.info("Парсинг завершён, новая дата сохранена: $newDate")
            } else {
                serverLogger.info("Расписание не обновилось, парсинг не требуется.")
            }
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при проверке обновления: ${e.message}")
        }
    }

    private suspend fun fetchUpdateDate(): String? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(updateCheckUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()
            val updateText = doc.select("div.col-sm-12 > h3").first()?.text()
            val dateRegex = """Изменения от (\d{2}\.\d{2}\.\d{4})""".toRegex()
            val date = dateRegex.find(updateText ?: "")?.groups?.get(1)?.value
            date ?: throw Exception("Дата обновления не найдена в первом контейнере")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при извлечении даты обновления: ${e.message}")
            null
        }
    }

    private fun readLastUpdateDate(): String? {
        return try {
            if (lastUpdateFile.exists()) {
                lastUpdateFile.readText().trim()
            } else {
                null
            }
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при чтении последней даты: ${e.message}")
            null
        }
    }

    private fun saveLastUpdateDate(date: String) {
        try {
            lastUpdateFile.writeText(date)
            serverLogger.info("Сохранена новая дата обновления: $date")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при сохранении даты: ${e.message}")
        }
    }

    private suspend fun fetchChangedRoutes(): Pair<String, List<String>>? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(scheduleChangesUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()

            val latestChangeSection = doc.select("div.row").first { it.select("h3").text().startsWith("Изменения от") }
            val date = latestChangeSection.select("h3").text().removePrefix("Изменения от ")
            val transportTypeElement = latestChangeSection.nextElementSibling()!!.select("span.list-group-item.noborder").first()
            val transportType = transportTypeElement!!.text()
            val changedRoutes = latestChangeSection.nextElementSibling()!!.nextElementSibling()!!
                .select("ul.nav.nav-pills > li > a")
                .map { it.text() }

            serverLogger.info("Извлечены изменения от $date для $transportType: $changedRoutes")
            Pair(transportType, changedRoutes)
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при извлечении изменённых маршрутов: ${e.message}")
            null
        }
    }

    suspend fun parseAndSaveToDb() {
        val url = "https://zippybus.com/by/grodno"
        val parse = Parse()
        val dbFolder = File(dataFolder, "database")
        val tempDbFile = File(dbFolder, "grodno_bus_temp.db")
        val finalDbFile = File(dbFolder, "grodno_bus.db")

        if (!finalDbFile.exists()) {
            serverLogger.warning("Основная база данных не найдена. Выполняется полный парсинг.")
            performFullParse(parse, url, tempDbFile, finalDbFile)
            return
        }

        val changedRoutesInfo = fetchChangedRoutes()
        if (changedRoutesInfo == null || changedRoutesInfo.second.isEmpty()) {
            serverLogger.info("Изменений в расписании не найдено. Пропускаем обновление.")
            return
        }

        val (transportType, changedRoutes) = changedRoutesInfo
        serverLogger.info("Обновление БД $transportType routes: $changedRoutes")

        try {
            Class.forName("org.sqlite.JDBC")
            serverLogger.info("SQLite JDBC успешная регистрация")
        } catch (e: ClassNotFoundException) {
            serverLogger.severe("SQLite JDBC драйвер не найден: ${e.message}")
            return
        }

        try {
            finalDbFile.copyTo(tempDbFile, overwrite = true)
            serverLogger.info("Основная база скопирована во временную: ${tempDbFile.absolutePath}")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при копировании базы данных: ${e.message}")
            return
        }

        val connection = try {
            DriverManager.getConnection("jdbc:sqlite:${tempDbFile.absolutePath}")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при подключении к временной базе данных: ${e.message}")
            return
        }

        try {
            val categoryId: Int = connection.createStatement().executeQuery(
                "SELECT id FROM Category WHERE nameCategory = ?"
            ).use { rs ->
                val stmt = connection.prepareStatement("SELECT id FROM Category WHERE nameCategory = ?")
                stmt.setString(1, transportType)
                val resultSet = stmt.executeQuery()
                if (resultSet.next()) resultSet.getInt("id") else {
                    serverLogger.severe("Категория $transportType не найдена в базе данных")
                    connection.close()
                    return
                }
            }
            serverLogger.info("ID категории для $transportType: $categoryId")

            changedRoutes.forEach { routeNumber ->
                val busId: Int? = connection.prepareStatement(
                    "SELECT id FROM Bus WHERE numBus = ? AND nameCategory_id = ?"
                ).use { stmt ->
                    stmt.setInt(1, routeNumber.toInt())
                    stmt.setInt(2, categoryId)
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getInt("id") else null
                }

                if (busId != null) {
                    connection.prepareStatement(
                        "DELETE FROM BusTime WHERE Station_id IN (SELECT id FROM Station WHERE Bus_id = ?)"
                    ).use { stmt ->
                        stmt.setInt(1, busId)
                        stmt.executeUpdate()
                    }
                    connection.prepareStatement(
                        "DELETE FROM Station WHERE Bus_id = ?"
                    ).use { stmt ->
                        stmt.setInt(1, busId)
                        stmt.executeUpdate()
                    }
                    connection.prepareStatement(
                        "DELETE FROM Bus WHERE id = ?"
                    ).use { stmt ->
                        stmt.setInt(1, busId)
                        stmt.executeUpdate()
                    }
                    serverLogger.info("Удалены данные для маршрута $routeNumber (ID: $busId)")
                }
            }

            changedRoutes.forEach { routeNumber ->
                connection.prepareStatement("INSERT INTO Bus (nameCategory_id, numBus) VALUES (?, ?)").apply {
                    setInt(1, categoryId)
                    setInt(2, routeNumber.toInt())
                    executeUpdate()
                    serverLogger.info("Добавлен маршрут $routeNumber в категорию $transportType (ID: $categoryId)")
                }

                val busId: Int = connection.createStatement().executeQuery(
                    "SELECT id FROM Bus WHERE numBus = ${routeNumber.toInt()} AND nameCategory_id = $categoryId"
                ).use { rs ->
                    rs.next(); rs.getInt("id")
                }

                val routes = if (transportType == "Автобус") parse.getBusRoutes(url) else parse.getTralRoutes(url)
                val routeData = routes.filter { it.number == routeNumber }
                routeData.forEach { route ->
                    connection.prepareStatement("INSERT INTO Station (station, path, link, Bus_id) VALUES (?, ?, ?, ?)").apply {
                        setString(1, route.station)
                        setString(2, route.path)
                        setString(3, route.link)
                        setInt(4, busId)
                        executeUpdate()
                        serverLogger.info("Добавлена остановка для маршрута $busId: station=${route.station}, path=${route.path}, link=${route.link}")
                    }
                }

                connection.createStatement().executeQuery("SELECT id, link FROM Station WHERE Bus_id = $busId").use { rs ->
                    while (rs.next()) {
                        val stationId: Int = rs.getInt("id")
                        val link: String = rs.getString("link")
                        val times: List<TimeTransport> = parse.getBusTime(link)
                        times.forEach { time ->
                            connection.prepareStatement("INSERT INTO BusTime (hour, time, day, Station_id) VALUES (?, ?, ?, ?)").apply {
                                setString(1, time.hour)
                                setString(2, time.min)
                                setString(3, time.day)
                                setInt(4, stationId)
                                executeUpdate()
                                serverLogger.info("Добавлено время для остановки $stationId: day=${time.day}, hour=${time.hour}, time=${time.min}")
                            }
                        }
                    }
                }
            }

            connection.close()
            serverLogger.info("Обновление временной базы завершено")

            if (finalDbFile.exists()) {
                finalDbFile.delete()
                serverLogger.info("Старая база данных удалена: ${finalDbFile.absolutePath}")
            }
            tempDbFile.renameTo(finalDbFile)
            serverLogger.info("Новая база данных установлена: ${finalDbFile.absolutePath}")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при обновлении базы данных: ${e.message}")
            try {
                connection.close()
            } catch (closeException: Exception) {
                serverLogger.severe("Ошибка при закрытии соединения: ${closeException.message}")
            }
            if (tempDbFile.exists()) {
                tempDbFile.delete()
                serverLogger.info("Временная база данных удалена из-за ошибки: ${tempDbFile.absolutePath}")
            }
        }
    }

    private fun performFullParse(parse: Parse, url: String, tempDbFile: File, finalDbFile: File) {
        serverLogger.info("Создание полной БД: ${tempDbFile.absolutePath}")

        try {
            Class.forName("org.sqlite.JDBC")
            serverLogger.info("SQLite JDBC успешная регистрация")
        } catch (e: ClassNotFoundException) {
            serverLogger.severe("SQLite JDBC драйвер не найден: ${e.message}")
            return
        }

        try {
            if (tempDbFile.exists()) {
                tempDbFile.delete()
                serverLogger.info("Существующий временный файл базы данных удалён: ${tempDbFile.absolutePath}")
            }
            tempDbFile.createNewFile()
            serverLogger.info("Временный файл базы данных создан: ${tempDbFile.absolutePath}")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при создании временного файла базы данных: ${e.message}")
            return
        }

        val connection = try {
            DriverManager.getConnection("jdbc:sqlite:${tempDbFile.absolutePath}")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при создании временной базы данных: ${e.message}")
            return
        }

        try {
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS Category (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nameCategory TEXT NOT NULL
                )
            """)
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS Bus (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nameCategory_id INTEGER NOT NULL,
                    numBus INTEGER NOT NULL,
                    FOREIGN KEY (nameCategory_id) REFERENCES Category(id) ON DELETE CASCADE
                )
            """)
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS Station (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    station TEXT NOT NULL,
                    path TEXT NOT NULL,
                    link TEXT NOT NULL,
                    Bus_id INTEGER NOT NULL,
                    FOREIGN KEY (Bus_id) REFERENCES Bus(id) ON DELETE CASCADE
                )
            """)
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS BusTime (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    hour TEXT NOT NULL,
                    time TEXT NOT NULL,
                    day TEXT NOT NULL,
                    Station_id INTEGER NOT NULL,
                    FOREIGN KEY (Station_id) REFERENCES Station(id) ON DELETE CASCADE
                )
            """)
            serverLogger.info("Временные таблицы БД созданы")

            val categories: List<String> = parse.transportName(url)
            categories.forEach { name: String ->
                connection.prepareStatement("INSERT INTO Category (nameCategory) VALUES (?)").apply {
                    setString(1, name)
                    executeUpdate()
                    serverLogger.info("Добавленные категории: $name")
                }
            }

            val busCategoryId: Int = connection.createStatement().executeQuery("SELECT id FROM Category WHERE nameCategory = 'Автобус'").use { rs ->
                rs.next(); rs.getInt("id")
            }
            val tralCategoryId: Int = connection.createStatement().executeQuery("SELECT id FROM Category WHERE nameCategory = 'Троллейбус'").use { rs ->
                rs.next(); rs.getInt("id")
            }
            serverLogger.info("ID: Bus=$busCategoryId, Trolleybus=$tralCategoryId")

            val busNumbers: List<String> = parse.busNumber(url)
            busNumbers.forEach { num: String ->
                connection.prepareStatement("INSERT INTO Bus (nameCategory_id, numBus) VALUES (?, ?)").apply {
                    setInt(1, busCategoryId)
                    setInt(2, num.toInt())
                    executeUpdate()
                    serverLogger.info("Добавлен автобус: number=$num, categoryId=$busCategoryId")
                }
            }

            val tralNumbers: List<String> = parse.tralNumber(url)
            tralNumbers.forEach { num: String ->
                connection.prepareStatement("INSERT INTO Bus (nameCategory_id, numBus) VALUES (?, ?)").apply {
                    setInt(1, tralCategoryId)
                    setInt(2, num.toInt())
                    executeUpdate()
                    serverLogger.info("Добавлен троллейбус: number=$num, categoryId=$tralCategoryId")
                }
            }

            val busRoutes: List<Route> = parse.getBusRoutes(url)
            busRoutes.forEach { route: Route ->
                val busId: Int? = connection.createStatement().executeQuery("SELECT id FROM Bus WHERE numBus = ${route.number.toInt()} AND nameCategory_id = $busCategoryId").use { rs ->
                    if (rs.next()) rs.getInt("id") else null
                }
                if (busId != null) {
                    connection.prepareStatement("INSERT INTO Station (station, path, link, Bus_id) VALUES (?, ?, ?, ?)").apply {
                        setString(1, route.station)
                        setString(2, route.path)
                        setString(3, route.link)
                        setInt(4, busId)
                        executeUpdate()
                        serverLogger.info("Добавленные остановки для автобуса $busId: station=${route.station}, path=${route.path}, link=${route.link}")
                    }
                } else {
                    serverLogger.warning("Автобус не найден: number=${route.number}")
                }
            }

            val tralRoutes: List<Route> = parse.getTralRoutes(url)
            tralRoutes.forEach { route: Route ->
                val tralId: Int? = connection.createStatement().executeQuery("SELECT id FROM Bus WHERE numBus = ${route.number.toInt()} AND nameCategory_id = $tralCategoryId").use { rs ->
                    if (rs.next()) rs.getInt("id") else null
                }
                if (tralId != null) {
                    connection.prepareStatement("INSERT INTO Station (station, path, link, Bus_id) VALUES (?, ?, ?, ?)").apply {
                        setString(1, route.station)
                        setString(2, route.path)
                        setString(3, route.link)
                        setInt(4, tralId)
                        executeUpdate()
                        serverLogger.info("Добавленные остановки для троллейбуса $tralId: station=${route.station}, path=${route.path}, link=${route.link}")
                    }
                } else {
                    serverLogger.warning("Троллейбус не найден: number=${route.number}")
                }
            }

            connection.createStatement().executeQuery("SELECT id, link FROM Station").use { rs ->
                while (rs.next()) {
                    val stationId: Int = rs.getInt("id")
                    val link: String = rs.getString("link")
                    val times: List<TimeTransport> = parse.getBusTime(link)
                    times.forEach { time: TimeTransport ->
                        connection.prepareStatement("INSERT INTO BusTime (hour, time, day, Station_id) VALUES (?, ?, ?, ?)").apply {
                            setString(1, time.hour)
                            setString(2, time.min)
                            setString(3, time.day)
                            setInt(4, stationId)
                            executeUpdate()
                            serverLogger.info("Добавлено время для остановки $stationId: day=${time.day}, hour=${time.hour}, time=${time.min}")
                        }
                    }
                }
            }

            connection.close()
            serverLogger.info("Временная БД готова")

            if (finalDbFile.exists()) {
                finalDbFile.delete()
                serverLogger.info("Старая база данных удалена: ${finalDbFile.absolutePath}")
            }
            tempDbFile.renameTo(finalDbFile)
            serverLogger.info("Новая база данных установлена: ${finalDbFile.absolutePath}")
        } catch (e: Exception) {
            serverLogger.severe("Ошибка при создании базы данных: ${e.message}")
            try {
                connection.close()
            } catch (closeException: Exception) {
                serverLogger.severe("Ошибка при закрытии соединения: ${closeException.message}")
            }
            if (tempDbFile.exists()) {
                tempDbFile.delete()
                serverLogger.info("Временная база данных удалена из-за ошибки: ${tempDbFile.absolutePath}")
            }
        }
    }

    class Parse {
        fun transportName(url: String): List<String> {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()
            val transportNames = mutableListOf<String>()
            val linkElements = doc.selectFirst("h3.list-group-item.noborder.text-center")
            linkElements?.let { transportNames.add(it.text()) }
            val linkElements2 = doc.select("h3.list-group-item.noborder.text-center").getOrNull(1)
            linkElements2?.let { transportNames.add(it.text()) }
            return transportNames
        }

        fun busNumber(url: String): List<String> {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()
            val busNumber = mutableListOf<String>()
            val table = doc.getElementsByTag("ul")
            val busTable = table.getOrNull(2)
            busTable?.children()?.forEach { linkElement -> busNumber.add(linkElement.text()) }
            return busNumber
        }

        fun tralNumber(url: String): List<String> {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()
            val tralNumber = mutableListOf<String>()
            val table = doc.getElementsByTag("ul")
            val busTable = table.getOrNull(3)
            busTable?.children()?.forEach { linkElement -> tralNumber.add(linkElement.text()) }
            return tralNumber
        }

        fun getBusRoutes(url: String): List<Route> {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()
            val routes1List = mutableListOf<Route>()
            val routes2List = mutableListOf<Route>()
            val table = doc.getElementsByTag("ul")
            val busTable = table.getOrNull(2) ?: return emptyList()
            val bus = busTable.children()

            bus.forEach { linkElement ->
                val busNumber = linkElement.select("a")
                val link = busNumber.attr("href")
                val routeDoc = Jsoup.connect(link)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(10000)
                    .get()
                val path1 = routeDoc.select("h4").first()
                val containerElement1 = routeDoc.select("div.list-group.list-group-striped").first()
                val linkElements1 = containerElement1?.select("a") ?: return@forEach

                path1?.let { p1 ->
                    linkElements1.forEach { stationElement ->
                        val route = Route(busNumber.text(), p1.text(), stationElement.text(), stationElement.attr("href"))
                        routes1List.add(route)
                    }
                }

                val path2 = routeDoc.select("h4").getOrNull(1)
                val containerElement2 = routeDoc.select("div.list-group.list-group-striped").getOrNull(1)
                val linkElements2 = containerElement2?.select("a") ?: return@forEach

                path2?.let { p2 ->
                    linkElements2.forEach { stationElement ->
                        val route = Route(busNumber.text(), p2.text(), stationElement.text(), stationElement.attr("href"))
                        routes2List.add(route)
                    }
                }
            }

            return routes1List + routes2List
        }

        fun getTralRoutes(url: String): List<Route> {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()
            val routes1List = mutableListOf<Route>()
            val routes2List = mutableListOf<Route>()
            val table = doc.getElementsByTag("ul")
            val busTable = table.getOrNull(3) ?: return emptyList()
            val bus = busTable.children()

            bus.forEach { linkElement ->
                val busNumber = linkElement.select("a")
                val link = busNumber.attr("href")
                val routeDoc = Jsoup.connect(link)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .timeout(10000)
                    .get()
                val path1 = routeDoc.select("h4").first()
                val containerElement1 = routeDoc.select("div.list-group.list-group-striped").first()
                val linkElements1 = containerElement1?.select("a") ?: return@forEach

                path1?.let { p1 ->
                    linkElements1.forEach { stationElement ->
                        val route = Route(busNumber.text(), p1.text(), stationElement.text(), stationElement.attr("href"))
                        routes1List.add(route)
                    }
                }

                val path2 = routeDoc.select("h4").getOrNull(1)
                val containerElement2 = routeDoc.select("div.list-group.list-group-striped").getOrNull(1)
                val linkElements2 = containerElement2?.select("a") ?: return@forEach

                path2?.let { p2 ->
                    linkElements2.forEach { stationElement ->
                        val route = Route(busNumber.text(), p2.text(), stationElement.text(), stationElement.attr("href"))
                        routes2List.add(route)
                    }
                }
            }

            return routes1List + routes2List
        }

        fun getBusTime(link: String): List<TimeTransport> {
            val doc = Jsoup.connect(link)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()
            val containerElement = doc.select("ul.nav.nav-pills")
            val linkElements = containerElement.select("li")
            val listDaysBus = mutableListOf<TimeTransport>()

            linkElements.forEach { element ->
                val allElements = element.select("a")
                val dayLink = allElements.attr("href")
                val day = allElements.text()

                if (day == "будни" || day == "выходные") {
                    val docTime = Jsoup.connect(dayLink)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .timeout(10000)
                        .get()
                    val containerElement2 = docTime.select("table.table-striped.scheduleTable")
                    val containerElement3 = containerElement2.select("tr")

                    containerElement3.forEach { linkElement ->
                        val hourElement = linkElement.select("td.hourHeader").first()
                        val hour = hourElement?.text() ?: return@forEach
                        val timeElement = linkElement.select("ul.nav.nav-pills").first()
                        val time = timeElement?.text() ?: return@forEach
                        listDaysBus.add(TimeTransport(day, hour, time))
                    }
                }
            }

            return listDaysBus
        }
    }
}

data class Route(val number: String, val path: String, val station: String, val link: String)
data class TimeTransport(val day: String, val hour: String, val min: String)