package app.willswitch.end;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Event sequences as measured on a Samsung A56, Android 16, with YouTube
 * 21.37 and Brave 1.95. See docs/measurements.md.
 */
public class FeedStateTest {

    private static final String RECYCLER = "android.support.v7.widget.RecyclerView";
    private static final String WEBVIEW = "android.webkit.WebView";

    private static final int HOME = 11;
    private static final int SHORTS = 22;
    private static final int PAGE = 33;

    private long clock = 10_000L;

    private FeedState youtube() {
        return new FeedState("com.google.android.youtube");
    }

    private void home(FeedState s, boolean arrived) {
        s.useList(HOME, RECYCLER, "results", true, 0, 371, 1080, 2340, arrived);
    }

    private void shorts(FeedState s) {
        s.useList(SHORTS, RECYCLER, "reel_recycler", false, 0, 101, 1080, 2163, true);
    }

    /** A RecyclerView event: real deltas, scroll offset always 0. */
    private int recycler(FeedState s, int dy, boolean locked, float limit) {
        clock += 108L;
        return s.observe(dy, 0, 0, true, locked, limit, clock);
    }

    /** A web page event: deltas unset, absolute offset. */
    private int web(FeedState s, int scrollY, boolean locked, float limit) {
        clock += 165L;
        return s.observe(-1, -1, scrollY, true, locked, limit, clock);
    }

    /** One pixel of movement, so the list has a position before the test starts. */
    private void settle(FeedState s) {
        recycler(s, 1, false, 99f);
        clock += 400L;
    }

    @Test
    public void theFirstSwipeOnAListCountsWhenNoPaneChanged() {
        FeedState s = youtube();
        s.useList(SHORTS, RECYCLER, "reel_recycler", false, 0, 101, 1080, 2163, false);

        recycler(s, 638, false, 9f);
        recycler(s, 920, false, 9f);
        recycler(s, 504, false, 9f);

        assertEquals(1f, s.progress, 0.01f);
    }

    @Test
    public void aPagerThatAddsUpAHairShortStillReachesTheLimit() {
        FeedState s = youtube();
        s.useList(SHORTS, RECYCLER, "reel_recycler", false, 0, 101, 1080, 2163, false);

        recycler(s, 1000, false, 1f);
        int result = recycler(s, 1061, false, 1f);   // one pixel short of the page

        assertTrue((result & FeedState.LIMIT_REACHED) != 0);
        assertEquals(0f, s.overshoot(), 0.01f);
    }

    @Test
    public void zeroDeltaFirstEventDoesNotFreezeThePosition() {
        // v16 to v18 took the first event, delta 0 and offset 0, as proof that
        // the list reports offsets, and then read offset 0 for ever.
        FeedState s = youtube();
        home(s, false);
        settle(s);

        assertEquals(0, s.observe(0, 0, 0, true, false, 3f, clock));
        assertEquals(FeedState.MOVED, recycler(s, 92, false, 3f));
        assertEquals(FeedState.MOVED, recycler(s, 200, false, 3f));
        assertEquals(293f, s.list.position, 0.01f);
    }

    @Test
    public void oneShortIsOneScreen() {
        FeedState s = youtube();
        shorts(s);
        settle(s);
        float before = s.progress;

        recycler(s, 638, false, 9f);
        recycler(s, 920, false, 9f);
        recycler(s, 504, false, 9f);   // 2062 px: the height of the pager

        assertEquals(1f, s.progress - before, 0.01f);
    }

    @Test
    public void readingBackCostsNothing() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        float start = s.progress;

        recycler(s, 1000, false, 9f);
        float afterDown = s.progress;
        recycler(s, -800, false, 9f);
        recycler(s, 800, false, 9f);

        assertTrue(afterDown > start);
        assertEquals(afterDown, s.progress, 0.0001f);
    }

    @Test
    public void aFlingCountsInFull() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        float start = s.progress;

        recycler(s, 1348, false, 9f);
        recycler(s, 917, false, 9f);
        recycler(s, 772, false, 9f);

        assertEquals((1348 + 917 + 772) / 1969f, s.progress - start, 0.01f);
    }

    @Test
    public void theEndIsPinnedAtTheExactPixelOfTheLimit() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        float offset = s.list.position;

        recycler(s, 1969, false, 2f);                       // one screen
        int result = recycler(s, 1969 + 500, false, 2f);    // crosses the limit by 500 px

        assertTrue((result & FeedState.LIMIT_REACHED) != 0);
        assertEquals(2f, s.progress, 0.0001f);
        assertEquals(offset + 2 * 1969f, s.list.endPosition, 2f);
        assertEquals(500f, s.overshoot(), 2f);
    }

    @Test
    public void movementThatIsNotTheUsersIsNeverCounted() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        float start = s.progress;

        clock += 108L;
        s.observe(600, 0, 0, false, false, 9f, clock);   // autoplay, a jump, a refresh
        recycler(s, 100, false, 9f);

        assertEquals(100 / 1969f, s.progress - start, 0.001f);
    }

    @Test
    public void aListComingBackOnScreenRestoresItselfWithoutCounting() {
        // Measured: tapping the Home tab puts the list back where it was with
        // one scroll of more than a thousand pixels.
        FeedState s = youtube();
        home(s, true);

        assertEquals(0, recycler(s, 1159, false, 3f));
        assertEquals(0f, s.progress, 0.0001f);
    }

    @Test
    public void aRestoredListIsNotAnOvershoot() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        s.lockedUntil = Long.MAX_VALUE;
        s.pinEndHere();

        shorts(s);
        home(s, true);                       // back to Home, the list restores itself
        recycler(s, 1159, true, 3f);

        assertEquals(0f, s.overshoot(), 0.01f);
    }

    @Test
    public void anotherListOfAnEndedFeedEndsWhereItStands() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        s.lockedUntil = Long.MAX_VALUE;
        s.pinEndHere();

        shorts(s);
        clock += 108L;
        s.observe(1, 0, 0, true, true, 3f, clock);   // arrives
        clock += 400L;
        recycler(s, 638, true, 3f);
        recycler(s, 920, true, 3f);
        recycler(s, 504, true, 3f);

        assertEquals(2062f, s.overshoot(), 1f);
    }

    @Test
    public void scrollingBackFromPastTheEndBringsTheOvershootToZero() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        s.lockedUntil = Long.MAX_VALUE;
        s.pinEndHere();

        recycler(s, 700, true, 3f);
        assertEquals(700f, s.overshoot(), 0.01f);
        recycler(s, -700, true, 3f);
        assertEquals(0f, s.overshoot(), 0.01f);
        recycler(s, -300, true, 3f);
        assertEquals(0f, s.overshoot(), 0.01f);
        assertEquals(-300f, s.signedOvershoot(), 0.01f);
    }

    @Test
    public void aWebPageIsReadFromItsAbsoluteOffset() {
        FeedState s = new FeedState("com.brave.browser#youtube.com");
        s.useList(PAGE, WEBVIEW, null, true, 0, 101, 1080, 2140, false);

        web(s, 20, false, 9f);      // baseline, and the arrival window opens
        clock += 400L;
        web(s, 288, false, 9f);
        web(s, 911, false, 9f);

        assertEquals((911 - 20) / 2039f, s.progress, 0.01f);
        assertEquals(911f, s.list.position, 0.01f);
    }

    @Test
    public void aNewPageInTheSameTabStartsFromItsTop() {
        FeedState s = new FeedState("com.brave.browser#youtube.com");
        s.useList(PAGE, WEBVIEW, null, true, 0, 101, 1080, 2140, false);
        web(s, 20, false, 9f);
        clock += 400L;
        web(s, 8152, false, 99f);
        s.lockedUntil = Long.MAX_VALUE;
        s.pinEndHere();

        web(s, 0, true, 9f);        // a link was opened: several screens up in one step
        web(s, 300, true, 9f);

        assertEquals(300f, s.overshoot(), 0.01f);
    }

    @Test
    public void theBurstFallbackIsSilentOnceAFeedHasScrolled() {
        FeedState s = youtube();
        s.onEntered(0L);
        home(s, false);
        settle(s);

        float counted = 0f;
        long t = clock + 5_000L;
        for (int i = 0; i < 12; i++) counted += s.measureContentBurst(t + i * 20L);

        assertEquals(0f, counted, 0.0001f);
    }

    @Test
    public void aSilentFeedStillCountsByBursts() {
        FeedState s = new FeedState("some.app.without.scroll.events");
        s.onEntered(0L);

        float counted = 0f;
        for (int i = 0; i < 6; i++) counted += s.measureContentBurst(5_000L + i * 20L);

        assertEquals(1f, counted, 0.0001f);
        assertFalse(s.scrollSeen);
    }

    @Test
    public void aResetLetsEveryListCountOnFromWhereItStands() {
        FeedState s = youtube();
        home(s, false);
        settle(s);
        recycler(s, 1969 * 3, false, 2f);
        s.lockedUntil = Long.MAX_VALUE;

        s.clearLock();
        recycler(s, 500, false, 2f);

        assertEquals(500 / 1969f, s.progress, 0.001f);
        assertFalse(s.list.endPinned);
    }
}
