package com.freedom.accessibility.browse

/**
 * Chooses the safe outcome of one return-to-feed verification sample.
 *
 * This policy contains no Android APIs so timeout and rejection branches can be
 * covered by local JVM tests. A restored position is accepted only on a confirmed
 * feed page with a matching snapshot that has passed the consecutive-sample check.
 */
internal object FeedReturnVerificationPolicy {
    enum class Decision {
        WAIT,
        RESTORED,
        PAUSE_ROOT_UNAVAILABLE,
        PAUSE_POSITION_UNCONFIRMED,
        PAUSE_NOT_FEED_PAGE
    }

    fun decide(
        rootAvailable: Boolean,
        isFeedPage: Boolean,
        positionMatches: Boolean,
        positionStable: Boolean,
        deadlineReached: Boolean
    ): Decision {
        if (!rootAvailable) {
            return if (deadlineReached) {
                Decision.PAUSE_ROOT_UNAVAILABLE
            } else {
                Decision.WAIT
            }
        }

        if (isFeedPage && positionMatches && positionStable) {
            return Decision.RESTORED
        }

        if (!deadlineReached) return Decision.WAIT

        return if (isFeedPage) {
            Decision.PAUSE_POSITION_UNCONFIRMED
        } else {
            Decision.PAUSE_NOT_FEED_PAGE
        }
    }
}
