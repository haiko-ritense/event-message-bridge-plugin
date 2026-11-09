/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.eventmessagebridge.web.rest

import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimoplugins.eventmessagebridge.service.EmbEvent
import com.ritense.valtimoplugins.eventmessagebridge.service.EventCaseStartService
import com.ritense.valtimoplugins.eventmessagebridge.service.EventResult
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Called by the Event Message Bridge, with the service token GZAC pushed to it, for every event it receives. */
@SkipComponentScan
@RestController
@RequestMapping(EventResource.EVENT_URL)
class EventResource(
    private val eventCaseStartService: EventCaseStartService,
) {
    @PostMapping
    fun receiveEvent(
        @RequestBody event: EmbEvent,
    ): ResponseEntity<EventResult> {
        if (event.eventId.isBlank() || event.eventItemId.isBlank()) {
            return ResponseEntity.badRequest().build()
        }
        return ResponseEntity.ok(eventCaseStartService.handle(event))
    }

    companion object {
        const val EVENT_URL = "/api/v1/plugin/event-message-bridge/event"
    }
}
