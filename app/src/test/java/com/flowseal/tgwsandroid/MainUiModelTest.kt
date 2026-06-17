package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.config.Appearance
import com.flowseal.tgwsandroid.proxy.HandshakeDiagnosticState
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiModelTest {
    @Test
    fun normalRouteModeLabelsMapToInternalModes() {
        val options = UserRouteModes.normalOptions.associateBy { it.title }

        assertEquals(NetworkRouteMode.AUTO, options.getValue("Авто").routeMode)
        assertEquals(NetworkRouteMode.DIRECT_FIRST, options.getValue("Быстрый Wi-Fi").routeMode)
        assertEquals(NetworkRouteMode.CF_FIRST, options.getValue("Совместимый").routeMode)
    }

    @Test
    fun autoRouteModeUsesCompactRecommendedSubtitle() {
        val auto = UserRouteModes.normalOptions.single { it.routeMode == NetworkRouteMode.AUTO }

        assertEquals("Авто", auto.title)
        assertEquals("Рекомендуется. Приложение само выбирает подходящий режим.", auto.subtitle)
    }

    @Test
    fun normalRouteModeOptionsDoNotUseLongLabelsOrCardBodies() {
        val labels = UserRouteModes.normalOptions.map { it.title }
        val subtitles = UserRouteModes.normalOptions.mapNotNull { it.subtitle }

        assertFalse(labels.contains("Автоматически — рекомендуется"))
        assertFalse(labels.contains("Совместимый Wi-Fi + мобильная сеть"))
        assertFalse(subtitles.any { it.contains("Приложение само выбирает лучший маршрут") })
        assertFalse(subtitles.any { it.contains("Использует быстрый прямой маршрут") })
        assertFalse(subtitles.any { it.contains("Использует совместимый маршрут через резервные домены") })
    }

    @Test
    fun routeModeButtonTextMarksSelectedModeWithoutLongBody() {
        val auto = UserRouteModes.normalOptions.single { it.routeMode == NetworkRouteMode.AUTO }
        val direct = UserRouteModes.normalOptions.single { it.routeMode == NetworkRouteMode.DIRECT_FIRST }

        assertEquals("✓ Авто\nРекомендуется. Приложение само выбирает подходящий режим.", UserRouteModes.buttonText(auto, selected = true))
        assertEquals("Быстрый Wi-Fi", UserRouteModes.buttonText(direct, selected = false))
    }

    @Test
    fun selectedRouteHelperTextChangesBySelectedMode() {
        assertEquals("Рекомендуется. Приложение само выбирает подходящий режим.", UserRouteModes.helperFor(NetworkRouteMode.AUTO))
        assertEquals("Для стабильного Wi-Fi.", UserRouteModes.helperFor(NetworkRouteMode.DIRECT_FIRST))
        assertEquals("Для мобильной сети.", UserRouteModes.helperFor(NetworkRouteMode.CF_FIRST))
    }


    @Test
    fun routeModeUiModelDerivesLabelAndDescriptionFromSameSelectedMode() {
        val cases = listOf(
            NetworkRouteMode.AUTO to ("Авто" to "Рекомендуется. Приложение само выбирает подходящий режим."),
            NetworkRouteMode.DIRECT_FIRST to ("Быстрый Wi-Fi" to "Для стабильного Wi-Fi."),
            NetworkRouteMode.CF_FIRST to ("Совместимый" to "Для мобильной сети."),
        )

        cases.forEach { (mode, expected) ->
            val model = UserRouteModes.uiModel(mode)
            assertEquals(expected.first, model.label)
            assertEquals(expected.second, model.description)
        }
    }

    @Test
    fun appearanceModesMapToResolvedNightModes() {
        assertEquals(ResolvedNightMode.FOLLOW_SYSTEM, AppearanceUiModels.nightMode(Appearance.AUTO))
        assertEquals(ResolvedNightMode.LIGHT, AppearanceUiModels.nightMode(Appearance.LIGHT))
        assertEquals(ResolvedNightMode.DARK, AppearanceUiModels.nightMode(Appearance.DARK))
    }


    @Test
    fun appColorSchemesSeparateLightAndDarkSurfaces() {
        val light = lightAppColorScheme()
        val dark = darkAppColorScheme()

        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(0xFFFFFFFF.toInt(), dark.surface)
        assertNotEquals(0xFFFFFFFF.toInt(), dark.surfaceContainer)
    }

    @Test
    fun darkSchemeAvoidsInvalidCardTextContrastPairs() {
        val dark = darkAppColorScheme()

        assertNotEquals(dark.surface, dark.onSurface)
        assertNotEquals(0xFFFFFFFF.toInt(), dark.surface)
        assertNotEquals(0xFF000000.toInt(), dark.onSurface)
        assertNotEquals(dark.surfaceVariant, dark.onSurfaceVariant)
    }

    @Test
    fun bottomNavColorModelDefinesSelectedAndUnselectedStates() {
        listOf(lightAppColorScheme(), darkAppColorScheme()).forEach { scheme ->
            val nav = bottomNavColorModel(scheme)

            assertEquals(scheme.surfaceContainer, nav.background)
            assertEquals(scheme.primary, nav.selected)
            assertEquals(scheme.onSurfaceVariant, nav.unselected)
            assertEquals(scheme.primaryContainer, nav.activeIndicator)
            assertNotEquals(nav.selected, nav.unselected)
        }
    }

    @Test
    fun themeRowsUseClearDescriptions() {
        assertEquals("Следует системной теме.", AppearanceUiModels.description(Appearance.AUTO))
        assertEquals("Всегда использовать светлую тему.", AppearanceUiModels.description(Appearance.LIGHT))
        assertEquals("Всегда использовать тёмную тему.", AppearanceUiModels.description(Appearance.DARK))
    }


    @Test
    fun cfOnlyIsNotShownInNormalSettings() {
        assertFalse(UserRouteModes.normalOptions.any { it.routeMode == NetworkRouteMode.CF_ONLY })
    }


    @Test
    fun settingsDeveloperModeDoesNotUseMovedToDiagnosticsMessage() {
        val source = java.io.File("src/main/java/com/flowseal/tgwsandroid/MainActivity.kt").readText()
        assertFalse(source.contains("данные перенесены", ignoreCase = true))
        assertFalse(source.contains("перенесены во вкладку"))
        assertFalse(source.contains("Технические диагностические данные и служебные действия перенесены"))
    }

    @Test
    fun developerDiagnosticsMayExposeRawRouteMode() {
        assertEquals("CF_ONLY", DeveloperUiModel.routeModeValue(NetworkRouteMode.CF_ONLY, developerModeEnabled = true))
        assertEquals("Совместимый", DeveloperUiModel.routeModeValue(NetworkRouteMode.CF_ONLY, developerModeEnabled = false))
    }

    @Test
    fun developerModeHiddenByDefault() {
        assertFalse(DeveloperUiModel.DEFAULT_DEVELOPER_MODE_ENABLED)
    }

    @Test
    fun developerModeActionsAreAvailableOnlyForDiagnosticsSection() {
        assertTrue(DeveloperUiModel.developerActions.contains("Маршрут: детали"))
        assertTrue(DeveloperUiModel.developerActions.contains("Cloudflare: детали"))
        assertTrue(DeveloperUiModel.developerActions.contains("Direct/pool: детали"))
        assertTrue(DeveloperUiModel.developerActions.contains("Handshake: детали"))
        assertTrue(DeveloperUiModel.developerActions.contains("Счётчики"))
        assertFalse(DeveloperUiModel.developerActions.contains("Копировать ссылку прокси"))
        assertFalse(DeveloperUiModel.developerActions.contains("Копировать диагностику"))
        assertFalse(DeveloperUiModel.developerActions.contains("Очистить логи"))
    }



    @Test
    fun diagnosticsActionsArePlacedOnlyInDiagnosticsModel() {
        val diagnosticsActions = listOf(
            "Копировать диагностику",
            "Поделиться диагностикой",
            "Открыть лог",
            "Очистить логи",
            "Отправить тестовую телеметрию",
        )
        diagnosticsActions.forEach { action ->
            assertTrue(DiagnosticsScreenUiModel.actions.contains(action))
            assertFalse(MainScreenUiModel.actions.contains(action))
            assertFalse(SettingsScreenUiModel.actions.contains(action))
        }
        assertFalse(MainScreenUiModel.actions.contains("Копировать ссылку прокси"))
        assertFalse(MainScreenUiModel.actions.contains("Скопировать ссылку"))
        assertFalse(SettingsScreenUiModel.actions.contains("Копировать ссылку прокси"))
    }


    @Test
    fun developerModeControlsDiagnosticsSectionsAndTelemetryAction() {
        val offSections = DiagnosticsScreenUiModel.sections(developerModeEnabled = false)
        assertFalse(offSections.any { it.developerOnly })
        assertTrue(DiagnosticsScreenUiModel.actions(developerModeEnabled = false).isEmpty())

        val onSections = DiagnosticsScreenUiModel.sections(developerModeEnabled = true)
        DiagnosticsScreenUiModel.developerSections.forEach { title ->
            assertTrue(onSections.any { it.title == title && it.developerOnly && it.collapsedByDefault })
        }
        assertTrue(DiagnosticsScreenUiModel.actions(developerModeEnabled = true).contains("Отправить тестовую телеметрию"))
    }

    @Test
    fun diagnosticsActionsAreSingleTopActionsCard() {
        val sections = DiagnosticsScreenUiModel.sections(developerModeEnabled = true)
        assertEquals("Проверка подключения", sections[0].title)
        assertEquals("Что можно сделать", sections[1].title)
        val actionSections = sections.filter { it.actions.isNotEmpty() }
        val developerActionSections = actionSections.filter { it.title == "Инструменты разработчика" }
        assertEquals(1, developerActionSections.size)
        assertEquals(DiagnosticsScreenUiModel.actions(developerModeEnabled = true), developerActionSections.single().actions)
        assertTrue(sections.indexOfFirst { it.title == "Инструменты разработчика" } < sections.indexOfFirst { it.title == "Маршрут: детали" })
        assertTrue(actionSections.single { it.title == "Инструменты разработчика" }.actions.containsAll(listOf("Копировать диагностику", "Поделиться диагностикой", "Открыть лог", "Очистить логи")))
    }

    @Test
    fun normalDiagnosticsIsAvailabilityDashboardWithoutDebugDump() {
        val sections = DiagnosticsScreenUiModel.sections(developerModeEnabled = false)
        val titles = sections.map { it.title }
        assertTrue(titles.contains("Проверка подключения"))
        assertTrue(sections.first { it.title == "Проверка подключения" }.actions.contains("Проверить сейчас"))
        assertTrue(titles.contains("Что можно сделать"))
        assertFalse(titles.contains("Последняя ошибка"))
        assertFalse(titles.contains("Ошибки за последнее время"))
        assertFalse(titles.contains("Логи"))
        assertFalse(titles.contains("Анонимная диагностика"))
        assertFalse(sections.any { it.actions.contains("Переподключить") })
        assertTrue(DiagnosticsScreenUiModel.actions(developerModeEnabled = false).isEmpty())
    }

    @Test
    fun diagnosticsRecommendationsAreCappedInUiSource() {
        val source = java.io.File("src/main/java/com/flowseal/tgwsandroid/MainActivity.kt").readText()
        assertTrue(source.contains("}.take(3)"))
    }

    @Test
    fun diagnosticsExportAndDeveloperRawDetailsRemainModeled() {
        assertTrue(DiagnosticsScreenUiModel.actions.contains("Копировать диагностику"))
        assertTrue(DiagnosticsScreenUiModel.actions.contains("Поделиться диагностикой"))
        assertTrue(DiagnosticsScreenUiModel.developerSections.containsAll(listOf("Маршрут: детали", "Cloudflare: детали", "Direct/pool: детали", "Handshake: детали", "Счётчики")))
    }

    @Test
    fun diagnosticsCheckNowRemainsAvailableWhenProxyIsOff() {
        val sections = DiagnosticsScreenUiModel.sections(developerModeEnabled = false)
        val checkSection = sections.single { it.title == "Проверка подключения" }
        assertTrue(checkSection.actions.contains(DiagnosticsScreenUiModel.CHECK_NOW_ACTION))

        val source = java.io.File("src/main/java/com/flowseal/tgwsandroid/MainActivity.kt").readText()
        assertTrue(source.contains("diagnosticsCheckButton.isEnabled = !diagnosticsCheckInProgress"))
        assertFalse(source.contains("Сначала включите прокси на главном экране"))
        val checkBody = source.substringAfter("private fun runDiagnosticsAvailabilityCheck()").substringBefore("private fun updateDiagnosticsDashboard()")
        assertFalse(checkBody.contains("startProxyService"))
        assertFalse(checkBody.contains("Toast.makeText"))
    }

    @Test
    fun diagnosticsProxyOffTextIsNeutralAndActionable() {
        assertEquals("Прокси выключен", DiagnosticsScreenUiModel.PROXY_OFF_STATUS)
        assertEquals("Не проверено", DiagnosticsScreenUiModel.PROXY_OFF_AVAILABILITY)
        assertEquals("Включите прокси на главном экране.", DiagnosticsScreenUiModel.PROXY_OFF_RECOMMENDATION)

        val source = java.io.File("src/main/java/com/flowseal/tgwsandroid/MainActivity.kt").readText()
        assertTrue(source.contains("!running -> \"Прокси выключен\""))
        assertTrue(source.contains("!running -> \"Не проверено\""))
        assertTrue(source.contains("if (!running) add(\"Включите прокси на главном экране.\")"))
        assertTrue(source.contains("}.take(3)"))
    }

    @Test
    fun diagnosticsCountersAreCompactGroupedSummaryOnly() {
        assertEquals(listOf("Подключения", "Маршруты", "Сеть", "Handshake", "Очереди и пул"), DiagnosticsScreenUiModel.compactCounterGroups)
        val sections = DiagnosticsScreenUiModel.sections(developerModeEnabled = true)
        assertTrue(sections.any { it.title == "Счётчики" && it.developerOnly })
        assertFalse(DiagnosticsScreenUiModel.sections(developerModeEnabled = false).any { it.title == "Счётчики" })

        val source = java.io.File("src/main/java/com/flowseal/tgwsandroid/MainActivity.kt").readText()
        assertTrue(source.contains("developerCountersSummary()"))
        assertTrue(source.contains("Нет заметных событий."))
        assertTrue(source.contains("rows.take(5)"))
        assertFalse(source.contains("Показать все счётчики"))
        assertFalse(source.contains("setTitle(\"Счётчики\")"))
    }

    @Test
    fun diagnosticsRawDataStaysInExportAndLogsOnly() {
        val source = java.io.File("src/main/java/com/flowseal/tgwsandroid/MainActivity.kt").readText()
        assertTrue(source.contains("ProxyForegroundService.State.diagnosticReport()"))
        assertTrue(DiagnosticsScreenUiModel.actions.contains("Копировать диагностику"))
        assertTrue(DiagnosticsScreenUiModel.actions.contains("Поделиться диагностикой"))
        assertTrue(DiagnosticsScreenUiModel.actions.contains("Открыть лог"))
        assertFalse(source.contains("raw counter", ignoreCase = true))
        assertFalse(source.contains("Показать все счётчики"))
    }

    @Test
    fun telemetryActionIsRussianOnly() {
        assertTrue(DiagnosticsScreenUiModel.actions.contains("Отправить тестовую телеметрию"))
        assertFalse((MainScreenUiModel.actions + SettingsScreenUiModel.actions + DiagnosticsScreenUiModel.actions).contains("Send test telemetry"))
    }

    @Test
    fun diagnosticsDeveloperSectionsPreserveRawDetails() {
        assertTrue(DiagnosticsScreenUiModel.developerSections.contains("Маршрут: детали"))
        assertTrue(DiagnosticsScreenUiModel.developerSections.contains("Cloudflare: детали"))
        assertTrue(DiagnosticsScreenUiModel.developerSections.contains("Direct/pool: детали"))
        assertTrue(DiagnosticsScreenUiModel.developerSections.contains("Счётчики"))
        assertTrue(DiagnosticsScreenUiModel.actions.contains("Открыть лог"))
    }
    @Test
    fun developerModeDoesNotExposeUpstreamManualCheckText() {
        assertFalse(DeveloperUiModel.developerActions.any { it.contains("upstream", ignoreCase = true) })
        assertFalse(DeveloperUiModel.developerActions.any { it.contains("check_upstream", ignoreCase = true) })
        assertFalse(DeveloperUiModel.developerActions.contains("Статус upstream-файлов"))
    }

    @Test
    fun batteryAndQuickSettingsInstructionsAreRussianAndCompact() {
        assertEquals("Батарея без ограничений", SettingsUiText.BATTERY_BACKGROUND_TITLE)
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("автозапуск"))
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("Без ограничений"))
        assertTrue(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("Без ограничений"))
        assertEquals("Статус: Без ограничений", SettingsUiText.batteryStatusLine("Без ограничений"))
        assertFalse(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("Чтобы прокси не останавливался"))
        assertFalse(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("Чтобы прокси не останавливался"))
        assertEquals("Кнопка в шторке", SettingsUiText.QS_TILE_TITLE)
        assertTrue(SettingsUiText.QS_TILE_TEXT.contains("быстрый переключатель"))
    }


    @Test
    fun recommendationCardsUseShortActionableText() {
        assertEquals("Позже", RecommendationUiText.DISMISS_ACTION)
        assertEquals("Включить уведомления", RecommendationUiText.cards.getValue("notifications").first)
        assertEquals("Так будет проще видеть состояние подключения", RecommendationUiText.cards.getValue("notifications").second)
        assertEquals("Батарея без ограничений", RecommendationUiText.cards.getValue("battery").first)
        assertEquals("Разрешите TG WS работать в фоне", RecommendationUiText.cards.getValue("battery").second)
        assertEquals("Кнопка в шторке", RecommendationUiText.cards.getValue("quick_settings").first)
        assertFalse(RecommendationUiText.cards.values.any { (title, subtitle) ->
            listOf(title, subtitle).any { text ->
                text.contains("Cloudflare", ignoreCase = true) ||
                    text.contains("OEM", ignoreCase = true) ||
                    text.contains("провайдер", ignoreCase = true) ||
                    text.contains("винов", ignoreCase = true)
            }
        })
    }


    @Test
    fun stoppedHeroIgnoresStaleUnstableHealth() {
        val hero = MainHeroStateMapper.state(
            running = false,
            starting = false,
            failed = false,
            healthLabel = "Нестабильно",
            telegramReconnectWarning = false,
            telegramConnected = false,
        )

        assertEquals("Прокси выключен", hero.title)
        assertEquals(null, hero.primaryAction)
    }

    @Test
    fun stoppedHeroIgnoresStaleTelegramReconnectWarning() {
        val hero = MainHeroStateMapper.state(
            running = false,
            starting = false,
            failed = false,
            healthLabel = "Стабильно",
            telegramReconnectWarning = true,
            telegramConnected = false,
        )

        assertEquals("Прокси выключен", hero.title)
        assertEquals(null, hero.primaryAction)
    }

    @Test
    fun stoppedHeroIgnoresPreviousFailedStatus() {
        val hero = MainHeroStateMapper.state(
            running = false,
            starting = false,
            failed = true,
            healthLabel = "Нестабильно",
            telegramReconnectWarning = true,
            telegramConnected = false,
        )

        assertEquals("Прокси выключен", hero.title)
        assertEquals(null, hero.primaryAction)
    }

    @Test
    fun runningUnstableHeroOffersReconnect() {
        val hero = MainHeroStateMapper.state(
            running = true,
            starting = false,
            failed = false,
            healthLabel = "Нестабильно",
            telegramReconnectWarning = false,
            telegramConnected = false,
        )

        assertEquals("Подключение нестабильно", hero.title)
        assertEquals(null, hero.primaryAction)
        assertEquals(null, hero.secondaryAction)
    }

    @Test
    fun startingHeroHasNoDuplicateStartOrReconnectAction() {
        val hero = MainHeroStateMapper.state(
            running = false,
            starting = true,
            failed = true,
            healthLabel = "Нестабильно",
            telegramReconnectWarning = true,
            telegramConnected = false,
        )

        assertEquals("Подключаемся…", hero.title)
        assertEquals(null, hero.primaryAction)
        assertEquals(null, hero.secondaryAction)
    }

    @Test
    fun runningWithoutTelegramHeroConnectsTelegram() {
        val hero = MainHeroStateMapper.state(
            running = true,
            starting = false,
            failed = false,
            healthLabel = "Стабильно",
            telegramReconnectWarning = false,
            telegramConnected = false,
        )

        assertEquals("Почти готово", hero.title)
        assertEquals(null, hero.primaryAction)
        assertEquals(null, hero.secondaryAction)
    }

    @Test
    fun runningHealthyHeroOnlyOffersStopAsSecondary() {
        val hero = MainHeroStateMapper.state(
            running = true,
            starting = false,
            failed = false,
            healthLabel = "Стабильно",
            telegramReconnectWarning = false,
            telegramConnected = true,
        )

        assertEquals("Всё готово", hero.title)
        assertEquals(null, hero.primaryAction)
        assertEquals(null, hero.secondaryAction)
    }


    @Test
    fun stoppedMainActionsOnlyShowStart() {
        val actions = MainActionModelMapper.actions(proxyEnabled = false, settingsChangedPendingRestart = false)

        assertEquals(listOf("Включить"), actions.visibleActions)
        assertFalse(actions.visibleActions.contains("Отключить"))
        assertFalse(actions.visibleActions.contains("Перезапустить"))
        assertFalse(actions.visibleActions.contains("Подключить Telegram"))
    }

    @Test
    fun runningMainActionsAlwaysShowStopRestartAndTelegram() {
        val actions = MainActionModelMapper.actions(proxyEnabled = true, settingsChangedPendingRestart = false)

        assertEquals("Отключить", actions.primaryAction)
        assertEquals("Перезапустить", actions.restartAction)
        assertEquals("Подключить Telegram", actions.telegramAction)
    }

    @Test
    fun pendingRestartUsesExistingRestartActionOnly() {
        val actions = MainActionModelMapper.actions(proxyEnabled = true, settingsChangedPendingRestart = true)

        assertEquals(1, actions.visibleActions.count { it == "Перезапустить" })
        assertEquals("Перезапустите прокси, чтобы применить изменения", actions.restartNote)
    }

    @Test
    fun stoppedWithPendingDiagnosticsStillDoesNotShowRestart() {
        val hero = MainHeroStateMapper.state(
            running = false,
            starting = false,
            failed = true,
            healthLabel = "Нестабильно",
            telegramReconnectWarning = true,
            telegramConnected = false,
        )
        val actions = MainActionModelMapper.actions(proxyEnabled = false, settingsChangedPendingRestart = true)

        assertEquals("Прокси выключен", hero.title)
        assertEquals(listOf("Включить"), actions.visibleActions)
        assertFalse(actions.visibleActions.contains("Перезапустить"))
    }

    @Test
    fun mainHeroDoesNotExposeDiagnosticsAction() {
        val scenarios = listOf(
            MainHeroStateMapper.state(false, false, false, "Нестабильно", false, false),
            MainHeroStateMapper.state(true, false, false, "Стабильно", false, true),
            MainHeroStateMapper.state(true, false, false, "Нестабильно", false, false),
        )

        assertFalse(scenarios.any { it.primaryAction == "Диагностика" || it.secondaryAction == "Диагностика" })
    }

    @Test
    fun settingsLabelsUseSecretAndNoPermanentRestartAction() {
        assertEquals("Обновить secret", SettingsUiText.RESET_SECRET_TITLE)
        assertFalse(SettingsUiText.RESET_SECRET_TITLE.contains("Обновить подключение"))
        assertFalse(SettingsUiText.RESET_SECRET_TITLE.contains("Перезапустить прокси"))
    }


    @Test
    fun settingsBadgesFollowPriorityRules() {
        val rows = SettingsScreenModel.normalRows.associateBy { it.title }

        assertTrue(rows.containsKey(SettingsUiText.RESET_SECRET_TITLE))
        assertNotEquals(SettingsBadge.IMPORTANT, rows.getValue(SettingsUiText.RESET_SECRET_TITLE).badge)
        assertNotEquals(SettingsBadge.RECOMMENDED, rows.getValue(SettingsUiText.RESET_SECRET_TITLE).badge)
        assertEquals(SettingsBadge.IMPORTANT, rows.getValue(SettingsUiText.BATTERY_BACKGROUND_TITLE).badge)
        assertEquals(SettingsBadge.IMPORTANT, rows.getValue("Автозапуск").badge)
    }

    @Test
    fun settingsChoiceRowsExposeOneSelectedUserFacingValue() {
        assertEquals(listOf("Авто", "Быстрый Wi-Fi", "Совместимый"), SettingsScreenModel.connectionModeChoice.options)
        assertEquals(1, listOf(SettingsScreenModel.connectionModeChoice.selectedValue).count { it.isNotBlank() })
        assertEquals(listOf("Авто", "Светлая", "Тёмная"), SettingsScreenModel.themeChoice.options)
        assertEquals(1, listOf(SettingsScreenModel.themeChoice.selectedValue).count { it.isNotBlank() })
    }

    @Test
    fun settingsLayoutIncludesTelegramDatacentersSectionAndAction() {
        val section = SettingsScreenModel.dcSection(listOf("2:149.154.167.220", "4:149.154.167.220"))

        assertEquals("Датацентры Telegram", section.title)
        assertEquals("Прямые адреса Telegram DC для подключения.", section.subtitle)
        assertTrue(section.rows.any { it.title == "Добавить датацентр" })
    }

    @Test
    fun settingsDatacentersSectionIsPresentForDefaultEmptyAndMalformedConfig() {
        val cases = listOf(
            listOf("2:149.154.167.220", "4:149.154.167.220"),
            emptyList(),
            listOf("broken", "abc", "2:999.1.1.1"),
        )

        cases.forEach { dcIp ->
            val section = SettingsScreenModel.dcSection(dcIp)
            assertEquals("Датацентры Telegram", section.title)
            assertTrue(section.rows.any { it.title == "Добавить датацентр" })
        }
    }

    @Test
    fun settingsDatacentersSectionIsNotDeveloperOnlyAndIsOrderedAfterMtprotoBeforeAppearance() {
        val sections = SettingsScreenModel.sections(listOf("2:149.154.167.220")).map { it.title }
        val dcSection = SettingsScreenModel.dcSection(emptyList())

        assertFalse(dcSection.developerOnly)
        assertTrue(sections.indexOf("Telegram MTProto") < sections.indexOf("Датацентры Telegram"))
        assertTrue(sections.indexOf("Датацентры Telegram") < sections.indexOf("Внешний вид"))
    }

    @Test
    fun settingsDatacentersRowsShowFormattedDcAndDoNotExposeRawConfigStrings() {
        val rows = SettingsScreenModel.dcSection(listOf("2:149.154.167.220", "4:149.154.167.220")).rows
        val normalText = rows.joinToString(" ") { listOfNotNull(it.title, it.description, it.selectedValue).joinToString(" ") }

        assertTrue(rows.any { it.title == "DC 2" && it.description == "149.154.167.220" })
        assertTrue(rows.any { it.title == "DC 4" && it.description == "149.154.167.220" })
        assertFalse(normalText.contains("2:149.154.167.220"))
    }

    @Test
    fun settingsDatacentersMalformedOnlyConfigShowsEmptyStateAndAction() {
        val rows = SettingsScreenModel.dcSection(listOf("broken", "abc", "2:999.1.1.1")).rows

        assertTrue(rows.any { it.title == "Датацентры не добавлены" })
        assertTrue(rows.any { it.title == "Добавить датацентр" })
        assertFalse(rows.any { it.title == "DC 2" })
    }

    @Test
    fun addDatacenterActionUsesAddDialogTitle() {
        assertEquals("Добавить датацентр", SettingsUiText.DC_ADD_TITLE)
    }


    @Test
    fun settingsChoiceModelsUpdateSelectedValuesImmediately() {
        assertEquals("Быстрый Wi-Fi", SettingsScreenModel.connectionModeChoice(NetworkRouteMode.DIRECT_FIRST).selectedValue)
        assertEquals("Совместимый", SettingsScreenModel.connectionModeChoice(NetworkRouteMode.CF_FIRST).selectedValue)
        assertEquals("Тёмная", SettingsScreenModel.themeChoice(Appearance.DARK).selectedValue)
    }

    @Test
    fun pendingRestartActionIsNotDuplicated() {
        val actions = MainActionModelMapper.actions(proxyEnabled = true, settingsChangedPendingRestart = true)
        assertEquals(listOf("Отключить", "Перезапустить", "Подключить Telegram"), actions.visibleActions)
        assertEquals(1, actions.visibleActions.count { it == "Перезапустить" })
        assertEquals(MainActionModelMapper.PENDING_RESTART_NOTE, actions.restartNote)
    }

    @Test
    fun adaptiveSettingsRowsKeepTextFieldsSeparate() {
        val rows = SettingsScreenModel.normalRows.associateBy { it.title }
        val battery = rows.getValue(SettingsUiText.BATTERY_BACKGROUND_TITLE)
        assertEquals("Разрешите TG WS работать в фоне, чтобы прокси не отключался при заблокированном экране.", battery.description)
        assertEquals("Нужно разрешить", battery.status)
        assertEquals(SettingsBadge.IMPORTANT, battery.badge)
        assertFalse(battery.title.contains(battery.description.orEmpty()))

        val notifications = rows.getValue("Уведомления")
        assertEquals("Показывают состояние подключения.", notifications.description)
        assertEquals("Включено", notifications.status)
        assertEquals(SettingsBadge.RECOMMENDED, notifications.badge)
        assertFalse(notifications.title.contains(notifications.description.orEmpty()))
    }

    @Test
    fun normalSettingsModelDoesNotExposeInternalRouteValues() {
        val normalText = SettingsScreenModel.normalRows.joinToString(" ") { listOfNotNull(it.title, it.selectedValue, it.badge?.label).joinToString(" ") }
        listOf("direct_first", "cf_first", "cf_only", "pool", "fallback").forEach { internalValue ->
            assertFalse(normalText.contains(internalValue))
        }
    }


    @Test
    fun anonymousDiagnosticsRemainsSwitchControlInSettingsModel() {
        val row = SettingsScreenModel.normalRows.single { it.title == "Анонимная диагностика" }

        assertEquals(SettingsRowKind.SWITCH, row.kind)
    }

    @Test
    fun settingsConnectionModeOptionsAreExactlyUserFacingLabels() {
        assertEquals(listOf("Авто", "Быстрый Wi-Fi", "Совместимый"), SettingsUiText.connectionModeOptions)
        assertEquals(3, SettingsUiText.connectionModeDialogDescriptions.size)
        assertFalse(SettingsUiText.connectionModeOptions.any { it in listOf("direct_first", "cf_first", "cf_only", "pool", "fallback") })
    }

    @Test
    fun settingsThemeOptionsAreExactlyUserFacingLabels() {
        assertEquals(listOf("Авто", "Светлая", "Тёмная"), SettingsUiText.themeOptions)
    }

    @Test
    fun compactThemeHelpersAreShortSelectorLabels() {
        assertEquals("системная тема", AppearanceUiModels.compactHelper(Appearance.AUTO))
        assertEquals("светлая тема", AppearanceUiModels.compactHelper(Appearance.LIGHT))
        assertEquals("тёмная тема", AppearanceUiModels.compactHelper(Appearance.DARK))
    }


    @Test
    fun switchColorsKeepThumbsTracksAndSurfacesDistinctInBothThemes() {
        val lightScheme = lightAppColorScheme()
        val lightColors = ControlTintModels.switchColors(lightScheme)
        val darkScheme = darkAppColorScheme()
        val darkColors = ControlTintModels.switchColors(darkScheme)

        assertNotEquals(lightColors.checkedThumb, lightColors.checkedTrack)
        assertNotEquals(lightColors.uncheckedTrack, lightScheme.surfaceContainer)
        assertNotEquals(lightColors.checkedTrack, lightColors.uncheckedTrack)

        assertNotEquals(darkColors.checkedThumb, darkColors.checkedTrack)
        assertNotEquals(darkColors.uncheckedTrack, darkScheme.surfaceContainer)
        assertNotEquals(darkColors.checkedThumb, darkScheme.background)
        assertNotEquals(darkColors.checkedThumb, darkScheme.surfaceContainer)
        assertNotEquals(darkColors.checkedTrack, darkColors.uncheckedTrack)
    }

    @Test
    fun switchColorsDefineSeparateDisabledCheckedAndUncheckedStates() {
        val colors = ControlTintModels.switchColors(darkAppColorScheme())

        assertNotEquals(colors.disabledCheckedThumb, colors.checkedThumb)
        assertNotEquals(colors.disabledUncheckedThumb, colors.uncheckedThumb)
        assertNotEquals(colors.disabledCheckedTrack, colors.disabledUncheckedTrack)
    }

    @Test
    fun darkCompoundControlTintsStayVisibleAgainstBackground() {
        val scheme = darkAppColorScheme()
        val colors = ControlTintModels.compoundButtonColors(scheme)

        assertNotEquals(colors.checked, scheme.background)
        assertNotEquals(colors.unchecked, scheme.background)
        assertNotEquals(colors.checked, colors.unchecked)
    }

    @Test
    fun darkRadioUsesVisibleCompoundTints() {
        val scheme = darkAppColorScheme()
        val colors = ControlTintModels.compoundButtonColors(scheme)

        assertNotEquals(colors.checked, scheme.background)
        assertNotEquals(colors.unchecked, scheme.background)
    }

    @Test
    fun darkThemeSelectorSelectedAndUnselectedColorsDiffer() {
        val scheme = darkAppColorScheme()
        val selected = ControlTintModels.segmentedButtonColors(scheme, selected = true)
        val unselected = ControlTintModels.segmentedButtonColors(scheme, selected = false)

        assertNotEquals(selected.background, unselected.background)
        assertNotEquals(selected.text, unselected.text)
        assertNotEquals(selected.stroke, unselected.stroke)
        assertEquals(listOf("Авто", "Светлая", "Тёмная"), SettingsUiText.themeOptions)
    }

    @Test
    fun stoppedProxyTelegramStatusSaysProxyStopped() {
        assertEquals("Прокси остановлен", ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = stats()))
    }

    @Test
    fun startingOrUnknownTelegramStatusIsChecking() {
        assertEquals("Проверяется", ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = null, checking = true))
        assertEquals("Проверяется", ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = null))
    }

    @Test
    fun runningWithoutSessionsWaitsForTelegramConnection() {
        assertEquals("Ожидает подключения", ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats()))
    }

    @Test
    fun activeSessionsShowTelegramConnected() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(connectionsActive = 1, lastRouteUsed = "direct-cold"),
            ),
        )
    }

    @Test
    fun lastRouteUsedWithoutCurrentFatalErrorsShowsConnected() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(lastRouteUsed = "direct-cold"),
            ),
        )
    }

    @Test
    fun networkNoneTelegramStatusHasNoNetwork() {
        assertEquals("Нет сети", ConnectionStatusMapper.status(running = true, networkStatus = "none", stats = stats()))
    }

    @Test
    fun wifiDirectWithOldCfErrorsDoesNotShowProblem() {
        val label = ConnectionStatusMapper.status(
            running = true,
            networkStatus = "Wi-Fi",
            stats = stats(
                effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue,
                lastRouteUsed = "direct-cold",
                cfProxyErrors = 10,
                cf429Count = 10,
                cfCooldownSkips = 10,
            ),
        )

        assertEquals("Подключён", label)
        assertFalse(label.contains("Проблема"))
    }

    @Test
    fun mobileCompatibleWithOld429AndSuccessfulRouteShowsConnected() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(
                    effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue,
                    cfProxyConnections = 2,
                    cf429Count = 3,
                    cfCooldownSkips = 2,
                    lastRouteUsed = "cf",
                ),
            ),
        )
    }

    @Test
    fun routeFailuresWithoutSuccessfulRouteAreUnstable() {
        assertEquals(
            "Нестабильное соединение",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(
                    connectionsTotal = 1,
                    connectionsBad = 1,
                    effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue,
                    directHealthState = "unhealthy",
                ),
            ),
        )
    }

    @Test
    fun normalTelegramStatusDoesNotUseOldEvaluativeLabels() {
        val labels = listOf(
            ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = stats()),
            ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats()),
            ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats(connectionsActive = 1)),
            ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats(connectionsTotal = 1, connectionsBad = 1)),
            ConnectionStatusMapper.status(running = true, networkStatus = "none", stats = stats()),
        )

        assertFalse(labels.contains("Проблема подключения"))
        assertFalse(labels.contains("Работает быстро"))
        assertFalse(labels.contains("Работает"))
        assertFalse(labels.contains("Работает медленно"))
        assertFalse(labels.contains("Перегружено"))
    }


    @Test
    fun lowBadHandshakeRatioDoesNotChangeTelegramStatus() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(connectionsTotal = 200, connectionsBad = 20, lastRouteUsed = "cf"),
            ),
        )
    }

    @Test
    fun recentBadHandshakeStormAsksToReconnectTelegram() {
        val recentStormStats = stats(
            connectionsTotal = 100,
            connectionsBad = 50,
            recentInvalidHandshakeCount = 100,
            recentAcceptedHandshakeCount = 0,
            lastAcceptedHandshakeTimeMs = 0,
            lastSuccessfulRouteTimeMs = 0,
        )

        assertTrue(recentStormStats.badHandshakeStormRecent)
        assertTrue(TelegramStatusUiText.showTelegramReconnectWarning(recentStormStats))
        assertEquals(
            "Нужно переподключить Telegram",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = recentStormStats,
            ),
        )
        assertEquals("Подключить Telegram заново", TelegramStatusUiText.actionText(recentStormStats))
    }

    @Test
    fun activeSessionsHideCumulativeBadHandshakeStorm() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(connectionsTotal = 9749, connectionsBad = 9611, connectionsActive = 1, lastRouteUsed = "cf"),
            ),
        )
    }

    @Test
    fun freshAcceptedHandshakeHidesCumulativeBadHandshakeStorm() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(
                    connectionsTotal = 9749,
                    connectionsBad = 9611,
                    lastAcceptedHandshakeTimeMs = System.currentTimeMillis(),
                ),
            ),
        )
    }

    @Test
    fun oldCumulativeBadHandshakeStormAloneDoesNotAskForever() {
        val cumulativeOnlyStats = stats(
            connectionsTotal = 9749,
            connectionsBad = 9611,
            recentInvalidHandshakeCount = 0,
            recentAcceptedHandshakeCount = 0,
            lastAcceptedHandshakeTimeMs = 0,
            lastSuccessfulRouteTimeMs = 0,
        )
        val status = ConnectionStatusMapper.status(
            running = true,
            networkStatus = "mobile",
            stats = cumulativeOnlyStats,
        )

        assertEquals(
            "Old cumulative bad-handshake counters without a recent storm must not show reconnect forever: " +
                "actualStatus=$status, recent=${cumulativeOnlyStats.badHandshakeStormRecent}, " +
                "cumulative=${cumulativeOnlyStats.badHandshakeStormCumulative}, " +
                "badHandshakeRatio=${cumulativeOnlyStats.badHandshakeRatio}",
            "Ожидает подключения",
            status,
        )
    }

    @Test
    fun cumulativeBadHandshakeStormDoesNotShowReconnectHelperWithoutRecentStorm() {
        val cumulativeOnlyStats = stats(
            connectionsTotal = 9749,
            connectionsBad = 9611,
            recentInvalidHandshakeCount = 0,
            recentAcceptedHandshakeCount = 0,
            lastAcceptedHandshakeTimeMs = 0,
            lastSuccessfulRouteTimeMs = 0,
        )

        assertTrue(cumulativeOnlyStats.badHandshakeStormCumulative)
        assertFalse(cumulativeOnlyStats.badHandshakeStormRecent)
        val status = ConnectionStatusMapper.status(
            running = true,
            networkStatus = "mobile",
            stats = cumulativeOnlyStats,
        )
        val helper = TelegramStatusUiText.helper(cumulativeOnlyStats, routeHelper = "route helper")

        val actionText = TelegramStatusUiText.actionText(cumulativeOnlyStats)
        val assertionDetails = "actualStatus=$status, actualHelper=$helper, actualAction=$actionText, " +
            "recent=${cumulativeOnlyStats.badHandshakeStormRecent}, " +
            "cumulative=${cumulativeOnlyStats.badHandshakeStormCumulative}, " +
            "badHandshakeRatio=${cumulativeOnlyStats.badHandshakeRatio}"

        assertFalse(assertionDetails, TelegramStatusUiText.showTelegramReconnectWarning(cumulativeOnlyStats))
        assertEquals(assertionDetails, "Ожидает подключения", status)
        assertFalse(assertionDetails, status.contains("Нужно переподключить Telegram"))
        assertEquals(assertionDetails, "route helper", helper)
        assertFalse(assertionDetails, helper.orEmpty().contains("Telegram отправляет неверные подключения"))
        assertEquals(assertionDetails, "Подключить Telegram", actionText)
        assertFalse(assertionDetails, actionText == "Подключить Telegram заново")
    }

    @Test
    fun telegramStormHelperMentionsReconnectWithoutRawProtocolText() {
        val helper = TelegramStatusUiText.helper(stats(connectionsTotal = 100, connectionsBad = 50, recentInvalidHandshakeCount = 100), routeHelper = null).orEmpty()

        assertTrue(helper.contains("актуальной ссылке"))
        assertFalse(helper.contains("Invalid MTProto handshake"))
        assertFalse(helper.contains("bad handshake", ignoreCase = true))
        assertFalse(helper.contains("MTProto"))
    }


    @Test
    fun manyInvalidWithRecentSuccessfulRouteIsBackgroundNoiseWithoutRecommendation() {
        val healthyStats = stats(
            connectionsTotal = 457_547,
            connectionsBad = 457_308,
            connectionsActive = 1,
            recentInvalidHandshakeCount = 2_226,
            recentAcceptedHandshakeCount = 0,
            lastSuccessfulRouteTimeMs = System.currentTimeMillis(),
            lastRouteUsed = "direct-pool",
            effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue,
            directHealthState = "healthy",
        )

        assertEquals(HandshakeDiagnosticState.BACKGROUND_NOISE.configValue, healthyStats.handshakeDiagnosticState)
        assertTrue(healthyStats.handshakeDiagnosticReason.contains("active_route_session") || healthyStats.handshakeDiagnosticReason.contains("recent_successful_route"))
        assertEquals("none", healthyStats.badHandshakeRecommendation)
        assertFalse(healthyStats.badHandshakeStormRecent)
        assertFalse(TelegramStatusUiText.showTelegramReconnectWarning(healthyStats))
    }

    @Test
    fun manyInvalidWithoutAcceptedOrSuccessfulRouteRecommendsReconnect() {
        val mismatchStats = stats(
            connectionsTotal = 200,
            connectionsBad = 180,
            recentInvalidHandshakeCount = 120,
            recentAcceptedHandshakeCount = 0,
            lastAcceptedHandshakeTimeMs = 0,
            lastSuccessfulRouteTimeMs = 0,
            lastRouteUsed = null,
        )

        assertEquals(HandshakeDiagnosticState.FATAL_SECRET_MISMATCH.configValue, mismatchStats.handshakeDiagnosticState)
        assertEquals(ProxyServerStats.BAD_HANDSHAKE_RECONNECT_RECOMMENDATION, mismatchStats.badHandshakeRecommendation)
        assertTrue(TelegramStatusUiText.showTelegramReconnectWarning(mismatchStats))
    }

    @Test
    fun highCumulativeBadHandshakeRatioWithHealthyRecentStateIsNotFatal() {
        val healthyLegacyCounters = stats(
            connectionsTotal = 457_547,
            connectionsBad = 457_308,
            recentInvalidHandshakeCount = 100,
            recentAcceptedHandshakeCount = 1,
            lastAcceptedHandshakeTimeMs = System.currentTimeMillis(),
            lastSuccessfulRouteTimeMs = System.currentTimeMillis(),
            lastRouteUsed = "direct-pool",
        )

        assertTrue(healthyLegacyCounters.badHandshakeStormCumulative)
        assertEquals(HandshakeDiagnosticState.BACKGROUND_NOISE.configValue, healthyLegacyCounters.handshakeDiagnosticState)
        assertEquals("none", healthyLegacyCounters.badHandshakeRecommendation)
        assertFalse(TelegramStatusUiText.showTelegramReconnectWarning(healthyLegacyCounters))
    }

    @Test
    fun handshakeDiagnosticsDoNotExposeAutomaticSecretResetState() {
        val mismatchStats = stats(recentInvalidHandshakeCount = 120, connectionsTotal = 200, connectionsBad = 180)

        assertEquals(HandshakeDiagnosticState.FATAL_SECRET_MISMATCH.configValue, mismatchStats.handshakeDiagnosticState)
        assertFalse(mismatchStats.handshakeDiagnosticReason.contains("reset", ignoreCase = true))
        assertFalse(mismatchStats.badHandshakeRecommendation.contains("reset", ignoreCase = true))
    }

    @Test
    fun persistedSecretAndCurrentProxyLinkAreNotModeledAsMutatedByHandshakeDiagnostics() {
        val healthyStats = stats(
            recentInvalidHandshakeCount = 2_226,
            lastSuccessfulRouteTimeMs = System.currentTimeMillis(),
            lastRouteUsed = "cf",
        )

        assertEquals(HandshakeDiagnosticState.BACKGROUND_NOISE.configValue, healthyStats.handshakeDiagnosticState)
        assertEquals("none", healthyStats.badHandshakeRecommendation)
        assertFalse(TelegramStatusUiText.DEVELOPER_RECOMMENDATION.contains("обнов", ignoreCase = true))
        assertFalse(TelegramStatusUiText.DEVELOPER_RECOMMENDATION.contains("reset", ignoreCase = true))
    }

    @Test
    fun oldCfCountersDoNotCreateBadHandshakeStatus() {
        assertEquals(
            "Ожидает подключения",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(cfProxyErrors = 46, cf429Count = 12, cfCooldownSkips = 9),
            ),
        )
    }

    @Test
    fun developerDiagnosticsTextMayExposeRawBadHandshakeStormInfo() {
        val diagnostic = "Invalid MTProto handshake storm=true badHandshakeRatio=0.986 bad=9611"

        assertTrue(diagnostic.contains("Invalid MTProto handshake storm"))
        assertTrue(diagnostic.contains("badHandshakeRatio"))
        assertTrue(TelegramStatusUiText.DEVELOPER_RECOMMENDATION.contains("Отключите proxy"))
    }

    @Test
    fun routeLabelMapsWifiDirectToFastWifi() {
        assertEquals(
            "Быстрый Wi-Fi",
            HomeRouteLabelMapper.label(
                networkStatus = "Wi-Fi",
                configuredRouteMode = NetworkRouteMode.DIRECT_FIRST,
                stats = stats(effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue),
            ),
        )
    }

    @Test
    fun routeLabelMapsMobileCfToCompatible() {
        assertEquals(
            "Совместимый",
            HomeRouteLabelMapper.label(
                networkStatus = "mobile",
                configuredRouteMode = NetworkRouteMode.CF_FIRST,
                stats = stats(effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue),
            ),
        )
    }

    @Test
    fun routeLabelKeepsAutoBeforeEffectiveRouteKnown() {
        assertEquals(
            "Автоматический выбор",
            HomeRouteLabelMapper.label(
                networkStatus = "Wi-Fi",
                configuredRouteMode = NetworkRouteMode.AUTO,
                stats = null,
            ),
        )
    }

    @Test
    fun routeLabelUsesEffectiveRouteForAutoWhenKnown() {
        assertEquals(
            "Быстрый Wi-Fi",
            HomeRouteLabelMapper.label(
                networkStatus = "Wi-Fi",
                configuredRouteMode = NetworkRouteMode.AUTO,
                stats = stats(effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue),
            ),
        )
    }


    @Test
    fun routeLabelMapsNoNetworkToNoNetwork() {
        assertEquals(
            "Нет сети",
            HomeRouteLabelMapper.label(
                networkStatus = "none",
                configuredRouteMode = NetworkRouteMode.AUTO,
                stats = stats(effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue),
            ),
        )
    }

    @Test
    fun mobileCompatibleRouteHelperIsSoftNonErrorText() {
        assertEquals(
            "На мобильной сети используется совместимый маршрут. Ping может быть выше.",
            HomeRouteLabelMapper.mobileCompatibleHelper("mobile", "Совместимый"),
        )
    }

    @Test
    fun secretUpdatedMessageIsNotShownByDefault() {
        assertFalse(SecretUpdatedMessageModel.visibleByDefault())
    }

    @Test
    fun routeModeChangeDoesNotShowSecretUpdatedMessage() {
        assertFalse(SecretUpdatedMessageModel.visibleAfterRouteModeChange())
    }

    @Test
    fun secretUpdatedMessageAppearsOnlyAfterConfirmedUpdateSecretAction() {
        assertTrue(SecretUpdatedMessageModel.visibleAfterConfirmedUpdateSecret(secretUpdated = true))
        assertFalse(SecretUpdatedMessageModel.visibleAfterConfirmedUpdateSecret(secretUpdated = false))
    }

    @Test
    fun secretUpdatedMessageIsNotShownAgainAfterConsumed() {
        assertFalse(SecretUpdatedMessageModel.visibleAfterConsumed())
    }

    @Test
    fun routeModeChangeWhileStoppedDoesNotCreateRestartWarning() {
        assertFalse(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = false))
    }

    @Test
    fun routeModeChangeWhileRunningCreatesRestartWarningOnlyIfRequired() {
        assertTrue(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = true, restartRequiredForRunningProxy = true))
        assertFalse(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = true, restartRequiredForRunningProxy = false))
    }

    @Test
    fun restartProxyActionDisabledWhileStopped() {
        assertFalse(PendingRestartModel.restartActionEnabled(proxyRunning = false))
        assertTrue(PendingRestartModel.restartActionEnabled(proxyRunning = true))
    }

    @Test
    fun savedRouteModeIsAppliedOnNextStart() {
        assertEquals(NetworkRouteMode.CF_FIRST, NetworkRouteMode.fromConfigValue(NetworkRouteMode.CF_FIRST.configValue))
        assertFalse(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = false))
    }

    @Test
    fun diagnosticsStillContainRawCounters() {
        val details = DeveloperUiModel.developerActions + listOf("CF", "429", "pool", "route state")
        assertTrue(details.joinToString(" ").contains("429"))
        assertTrue(details.joinToString(" ").contains("pool"))
    }

    private fun stats(
        connectionsTotal: Long = 0,
        connectionsActive: Int = 0,
        connectionsBad: Long = 0,
        wsConnectErrors: Long = 0,
        cfProxyConnections: Long = 0,
        cfProxyErrors: Long = 0,
        bytesUp: Long = 0,
        bytesDown: Long = 0,
        effectiveRouteMode: String = NetworkRouteMode.CF_FIRST.configValue,
        sessionTimeouts: Long = 0,
        sessionUnexpectedErrors: Long = 0,
        networkNoneEvents: Long = 0,
        cf429Count: Long = 0,
        cfCooldownSkips: Long = 0,
        directHealthState: String = "healthy",
        directHealthSuccesses: Long = 0,
        lastRouteUsed: String? = null,
        recentInvalidHandshakeCount: Long = 0,
        recentAcceptedHandshakeCount: Long = 0,
        lastAcceptedHandshakeTimeMs: Long = 0,
        lastSuccessfulRouteTimeMs: Long = 0,
    ): ProxyServerStats {
        val snapshot = ProxyServerStats()
        snapshot.connectionsTotal = connectionsTotal
        snapshot.connectionsActive = connectionsActive
        snapshot.connectionsBad = connectionsBad
        snapshot.wsConnectErrors = wsConnectErrors
        snapshot.cfProxyConnections = cfProxyConnections
        snapshot.cfProxyErrors = cfProxyErrors
        snapshot.bytesUp = bytesUp
        snapshot.bytesDown = bytesDown
        snapshot.poolHits = 0
        snapshot.poolMisses = 0
        snapshot.poolRefillErrors = 0
        snapshot.effectiveRouteMode = effectiveRouteMode
        snapshot.sessionTimeouts = sessionTimeouts
        snapshot.sessionUnexpectedErrors = sessionUnexpectedErrors
        snapshot.networkNoneEvents = networkNoneEvents
        snapshot.directHealthState = directHealthState
        snapshot.directHealthSuccesses = directHealthSuccesses
        snapshot.lastRouteUsed = lastRouteUsed
        snapshot.cf429Count = cf429Count
        snapshot.cfCooldownSkips = cfCooldownSkips
        snapshot.recentInvalidHandshakeCount = recentInvalidHandshakeCount
        snapshot.recentAcceptedHandshakeCount = recentAcceptedHandshakeCount
        snapshot.lastAcceptedHandshakeTimeMs = lastAcceptedHandshakeTimeMs
        snapshot.lastSuccessfulRouteTimeMs = lastSuccessfulRouteTimeMs

        return snapshot
    }
}
