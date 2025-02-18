/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.tabs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.browser.session.storage.RecoverableBrowserState
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.CustomTabConfig
import mozilla.components.browser.state.state.SessionState.Source
import mozilla.components.browser.state.state.recover.RecoverableTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import mozilla.components.concept.engine.manifest.WebAppManifest
import mozilla.components.feature.session.SessionUseCases.LoadUrlUseCase

/**
 * Contains use cases related to the tabs feature.
 */
class TabsUseCases(
    store: BrowserStore,
    sessionManager: SessionManager
) {
    /**
     * Contract for use cases that select a tab.
     */
    interface SelectTabUseCase {
        /**
         * Select given [session].
         */
        operator fun invoke(session: Session)

        /**
         * Select [Session] with the given [tabId].
         */
        operator fun invoke(tabId: String)
    }

    class DefaultSelectTabUseCase internal constructor(
        private val sessionManager: SessionManager
    ) : SelectTabUseCase {

        /**
         * Marks the provided session as selected.
         *
         * @param session The session to select.
         */
        override operator fun invoke(session: Session) {
            sessionManager.select(session)
        }

        /**
         * Marks the tab with the provided [tabId] as selected.
         */
        override fun invoke(tabId: String) {
            val session = sessionManager.findSessionById(tabId)
            if (session != null) {
                sessionManager.select(session)
            }
        }
    }

    /**
     * Contract for use cases that remove a tab.
     */
    interface RemoveTabUseCase {
        /**
         * Removes the session with the provided ID. This method
         * has no effect if the session doesn't exist.
         *
         * @param sessionId The ID of the session to remove.
         */
        operator fun invoke(sessionId: String)

        /**
         * Removes the provided session.
         *
         * @param session The session to remove.
         */
        operator fun invoke(session: Session)
    }

    /**
     * Default implementation of [RemoveTabUseCase], interacting
     * with [SessionManager].
     */
    class DefaultRemoveTabUseCase internal constructor(
        private val sessionManager: SessionManager
    ) : RemoveTabUseCase {

        /**
         * Removes the session with the provided ID. This method
         * has no effect if the session doesn't exist.
         *
         * @param sessionId The ID of the session to remove.
         */
        override operator fun invoke(sessionId: String) {
            val session = sessionManager.findSessionById(sessionId)
            if (session != null) {
                invoke(session)
            }
        }

        /**
         * Removes the provided session.
         *
         * @param session The session to remove.
         */
        override operator fun invoke(session: Session) {
            sessionManager.remove(session)
        }
    }

    class AddNewTabUseCase internal constructor(
        private val store: BrowserStore,
        private val sessionManager: SessionManager
    ) : LoadUrlUseCase {

        /**
         * Adds a new tab and loads the provided URL.
         *
         * @param url The URL to be loaded in the new tab.
         * @param flags the [LoadUrlFlags] to use when loading the provided URL.
         */
        override fun invoke(url: String, flags: LoadUrlFlags, additionalHeaders: Map<String, String>?) {
            this.invoke(url, true, true, null, flags)
        }

        /**
         * Adds a new tab and loads the provided URL.
         *
         * @param url The URL to be loaded in the new tab.
         * @param selectTab True (default) if the new tab should be selected immediately.
         * @param startLoading True (default) if the new tab should start loading immediately.
         * @param parentId the id of the parent tab to use for the newly created tab.
         * @param flags the [LoadUrlFlags] to use when loading the provided URL.
         * @param contextId the session context id to use for this tab.
         * @param engineSession (optional) engine session to use for this tab.
         * @return The ID of the created tab.
         */
        @Suppress("LongParameterList")
        operator fun invoke(
            url: String = "about:blank",
            selectTab: Boolean = true,
            startLoading: Boolean = true,
            parentId: String? = null,
            flags: LoadUrlFlags = LoadUrlFlags.none(),
            contextId: String? = null,
            engineSession: EngineSession? = null,
            source: Source = Source.NEW_TAB,
            customTabConfig: CustomTabConfig? = null,
            webAppManifest: WebAppManifest? = null
        ): String {
            val session = Session(url, false, source, contextId = contextId).apply {
                this.customTabConfig = customTabConfig
                this.webAppManifest = webAppManifest
            }

            val parent = parentId?.let { sessionManager.findSessionById(parentId) }
            sessionManager.add(session, selected = selectTab, engineSession = engineSession, parent = parent)

            // If an engine session is specified then loading will have already started
            // during sessionManager.add when linking the session to its engine session.
            if (startLoading && engineSession == null) {
                store.dispatch(EngineAction.LoadUrlAction(
                    session.id,
                    url,
                    flags
                ))
            }

            return session.id
        }
    }

    class AddNewPrivateTabUseCase internal constructor(
        private val store: BrowserStore,
        private val sessionManager: SessionManager
    ) : LoadUrlUseCase {

        /**
         * Adds a new private tab and loads the provided URL.
         *
         * @param url The URL to be loaded in the new private tab.
         * @param flags the [LoadUrlFlags] to use when loading the provided URL.
         */
        override fun invoke(url: String, flags: LoadUrlFlags, additionalHeaders: Map<String, String>?) {
            this.invoke(url, true, true, null, flags)
        }

        /**
         * Adds a new private tab and loads the provided URL.
         *
         * @param url The URL to be loaded in the new tab.
         * @param selectTab True (default) if the new tab should be selected immediately.
         * @param startLoading True (default) if the new tab should start loading immediately.
         * @param parentId the id of the parent tab to use for the newly created tab.
         * @param flags the [LoadUrlFlags] to use when loading the provided URL.
         * @param engineSession (optional) engine session to use for this tab.
         * @return The ID of the created tab.
         */
        @Suppress("LongParameterList")
        operator fun invoke(
            url: String = "about:blank",
            selectTab: Boolean = true,
            startLoading: Boolean = true,
            parentId: String? = null,
            flags: LoadUrlFlags = LoadUrlFlags.none(),
            engineSession: EngineSession? = null,
            source: Source = Source.NEW_TAB
        ): String {
            val session = Session(url, true, source)

            val parent = parentId?.let { sessionManager.findSessionById(parentId) }
            sessionManager.add(session, selected = selectTab, engineSession = engineSession, parent = parent)

            // If an engine session is specified then loading will have already started
            // during sessionManager.add when linking the session to its engine session.
            if (startLoading && engineSession == null) {
                store.dispatch(EngineAction.LoadUrlAction(
                    session.id,
                    url,
                    flags
                ))
            }

            return session.id
        }
    }

    /**
     * Use case for removing a list of tabs.
     */
    class RemoveTabsUseCase internal constructor(
        private val sessionManager: SessionManager
    ) {
        /**
         * Removes a specified list of tabs.
         */
        operator fun invoke(ids: List<String>) {
            sessionManager.removeListOfSessions(ids)
        }
    }

    class RemoveAllTabsUseCase internal constructor(
        private val sessionManager: SessionManager
    ) {
        operator fun invoke() {
            sessionManager.removeSessions()
        }
    }

    /**
     * Use case for removing all normal (non-private) tabs.
     */
    class RemoveNormalTabsUseCase internal constructor(
        private val sessionManager: SessionManager
    ) {
        /**
         * Removes all normal (non-private) tabs.
         */
        operator fun invoke() {
            sessionManager.removeNormalSessions()
        }
    }

    /**
     * Use case for removing all private tabs.
     */
    class RemovePrivateTabsUseCase internal constructor(
        private val sessionManager: SessionManager
    ) {
        /**
         * Removes all private tabs.
         */
        operator fun invoke() {
            sessionManager.removePrivateSessions()
        }
    }

    /**
     * Use case for restoring removed tabs ("undo").
     */
    class UndoTabRemovalUseCase(
        private val store: BrowserStore
    ) {
        /**
         * Restores the list of tabs in the undo history.
         */
        operator fun invoke() {
            store.dispatch(UndoAction.RestoreRecoverableTabs)
        }
    }

    /**
     * Use case for restoring tabs.
     */
    class RestoreUseCase(
        private val store: BrowserStore,
        private val sessionManager: SessionManager
    ) {
        /**
         * Restores the given list of [RecoverableTab]s.
         */
        operator fun invoke(tabs: List<RecoverableTab>, selectTabId: String? = null) {
            sessionManager.restore(tabs, selectTabId)
        }

        /**
         * Restores the given [RecoverableBrowserState].
         */
        operator fun invoke(state: RecoverableBrowserState) {
            invoke(state.tabs, state.selectedTabId)
        }

        /**
         * Restores the browsing session from the given [SessionStorage]. Also dispatches
         * [RestoreCompleteAction] on the [BrowserStore] once restore has been completed.
         */
        suspend operator fun invoke(storage: SessionStorage) = withContext(Dispatchers.IO) {
            val state = storage.restore()
            if (state != null) {
                withContext(Dispatchers.Main) {
                    invoke(state)
                }
            }
            store.dispatch(RestoreCompleteAction)
        }
    }

    val selectTab: SelectTabUseCase by lazy { DefaultSelectTabUseCase(sessionManager) }
    val removeTab: RemoveTabUseCase by lazy { DefaultRemoveTabUseCase(sessionManager) }
    val addTab: AddNewTabUseCase by lazy { AddNewTabUseCase(store, sessionManager) }
    val addPrivateTab: AddNewPrivateTabUseCase by lazy { AddNewPrivateTabUseCase(store, sessionManager) }
    val removeAllTabs: RemoveAllTabsUseCase by lazy { RemoveAllTabsUseCase(sessionManager) }
    val removeTabs: RemoveTabsUseCase by lazy { RemoveTabsUseCase(sessionManager) }
    val removeNormalTabs: RemoveNormalTabsUseCase by lazy { RemoveNormalTabsUseCase(sessionManager) }
    val removePrivateTabs: RemovePrivateTabsUseCase by lazy { RemovePrivateTabsUseCase(sessionManager) }
    val undo by lazy { UndoTabRemovalUseCase(store) }
    val restore: RestoreUseCase by lazy { RestoreUseCase(store, sessionManager) }
}
