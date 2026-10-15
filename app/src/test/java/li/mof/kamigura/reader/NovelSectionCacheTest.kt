package li.mof.kamigura.reader

import kotlinx.coroutines.*
import li.mof.kamigura.reader.novel.NovelSectionCache
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NovelSectionCacheTest {
    @Test fun foregroundJoinsPrefetchAndCompletedHtmlIsReused() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val calls=mutableListOf<Int>()
        val cache=NovelSectionCache(this) { page -> calls+=page; if(page==2){entered.complete(Unit);release.await()};"page$page" }
        try {
            cache.retainAround(1,4); assertEquals("page1",cache.get(1))
            cache.prefetchAround(1,4); entered.await()
            val foreground=async { cache.get(2) }; yield()
            assertEquals(1,calls.count{it==2}); release.complete(Unit)
            assertEquals("page2",foreground.await()); assertEquals("page2",cache.get(2))
            assertEquals(1,calls.count{it==2}); assertFalse(3 in calls)
        } finally {cache.close()}
    }
    @Test fun speculativeFailuresAreNotCachedAndForegroundCanRetry() = runBlocking {
        val failed=CompletableDeferred<Unit>(); var calls=0
        val cache=NovelSectionCache(this) { page -> if(page==1 && ++calls==1){failed.complete(Unit);throw IOException("offline")};"page$page" }
        try {
            cache.retainAround(0,2); cache.prefetchAround(0,2); failed.await(); yield()
            assertEquals("page1",cache.get(1)); assertEquals(2,calls)
        } finally {cache.close()}
    }
    @Test fun byteBudgetAndRetainedWindowEvictHtmlWithoutChangingItsContents() = runBlocking {
        val calls=mutableListOf<Int>()
        val cache=NovelSectionCache(this,maxBytes=12) {page->calls+=page;"abcd"}
        try {
            cache.retainAround(1,8); cache.get(0); cache.get(1); cache.get(0)
            assertEquals(listOf(0,1,0),calls)
            cache.retainAround(5,8)
            try {cache.get(0);fail("Outside window")}catch(_:IllegalStateException){}
            cache.get(5); cache.retainAround(1,8); cache.get(0)
            assertEquals(listOf(0,1,0,5,0),calls)
        } finally {cache.close()}
    }
    @Test fun leavingTheWindowAndClosingCancelPendingRequests() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val cancelled=CompletableDeferred<Unit>()
        val cache=NovelSectionCache(this) {page->
            if(page==1) {entered.complete(Unit);try{awaitCancellation()}finally{cancelled.complete(Unit)}}
            "page$page"
        }
        try {
            cache.retainAround(0,8); cache.prefetchAround(0,8); entered.await()
            cache.retainAround(5,8); cancelled.await()
            assertEquals("page5",cache.get(5))
            cache.close()
            try {cache.get(5);fail("Closed cache")}catch(_:IllegalStateException){}
        } finally {cache.close()}
    }
    @Test fun cancellingOneWaiterDoesNotCancelASharedRequest() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val cache=NovelSectionCache(this) {calls++;entered.complete(Unit);release.await();"html"}
        try {
            cache.retainAround(0,1)
            val first=launch{cache.get(0)};entered.await();first.cancelAndJoin()
            val next=async{cache.get(0)};release.complete(Unit)
            assertEquals("html",next.await());assertEquals(1,calls)
        } finally{cache.close()}
    }
}
