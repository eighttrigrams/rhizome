Feature: A video that goes on playing

  A video never plays inline. Where the embed used to be there is a still, and
  clicking it starts the video in one player that floats above the app. It goes
  on playing while the owner leaves the item, picks another one, or opens
  anything else — it goes away when the X is pressed, or when another video
  takes its place, and by nothing else.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Watchlist" in the search input
    And I press the "Enter" key
    Then I should see "Watchlist" in the lhs
    # Both items over REST, the way the hierarchy scenarios set their edges.
    # Two creations chained through the input is one hop more than this app's
    # keypress handling reliably survives — measured: the second Enter was
    # swallowed in one run out of three, and the scenario failed several steps
    # later on an item that had silently never been created. What is under test
    # here is the player.
    And "Watchlist" holds an item "A talk worth keeping" showing "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    And "Watchlist" holds an item "Another talk entirely" showing "https://www.youtube.com/watch?v=oHg5SJYRHA0"
    When I reload the app

  Scenario: A still stands where the embed used to be, and nothing plays there
    When I open the item "A talk worth keeping"
    Then the item should show a still for "dQw4w9WgXcQ"
    And nothing should be playing in the item view
    And nothing should be playing

  Scenario: Clicking the still starts the player, playing, in the bottom-left corner
    When I open the item "A talk worth keeping"
    And I click the video poster
    Then the player should be playing "dQw4w9WgXcQ"
    # A player that needs a second click is not what was asked for. Both halves
    # are needed: the URL asks to start, and the frame has to be allowed to.
    And the player should be asked to start on its own
    # Bottom left is the corner nothing else is in: REC and the vector-threshold
    # panel are top left, DANGER is top right.
    And the player should be in the "bottom-left" corner

  Scenario: Moving to another item leaves the player playing
    # The bug this is written against is a remount. An iframe React rebuilds,
    # or that is re-parented, reloads and plays from zero — and "an iframe is
    # present" passes on exactly that, so the node itself is held onto and
    # compared. Every hop below is a real navigation, not a redraw.
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I remember the player's iframe
    And I press Escape in the app
    Then the item view should be closed
    And the player should be the very same iframe
    When I go back to "Watchlist" in the lhs
    And I open the item "Another talk entirely"
    Then the item should show a still for "oHg5SJYRHA0"
    And the player should be the very same iframe
    And the player should be playing "dQw4w9WgXcQ"

  Scenario: Dragged into the bottom-right quadrant, it settles in that corner
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I remember the player's iframe
    And I drag the player into the "bottom-right" quadrant
    Then the player should be in the "bottom-right" corner
    # Dragging is the case the mounting rule exists for: moving the box by
    # re-parenting it would have reloaded the video on the way across.
    And the player should be the very same iframe

  Scenario: Opening a modal does not take it away
    # The third of the things that are not one of the two ways out. The edit
    # modal draws over the app at 1010/1011; the player is above it and goes on
    # playing, which is the point of watching something while you work.
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I remember the player's iframe
    And I open the edit modal
    Then the modal should still be open
    And the player should be the very same iframe
    And the player should be playing "dQw4w9WgXcQ"

  Scenario: The X closes it
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I close the player
    Then nothing should be playing
    # And the way back in is where it was.
    And the item should show a still for "dQw4w9WgXcQ"

  Scenario: A second video replaces the first rather than stacking on it
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I press Escape in the app
    And I go back to "Watchlist" in the lhs
    And I open the item "Another talk entirely"
    And I click the video poster
    Then there should be exactly one player
    And the player should be playing "oHg5SJYRHA0"

  Scenario: The player's QR is for what is playing, not for the item on screen
    # The handover this feature creates: by the time the owner wants the video
    # on his phone, the item it came from is routinely no longer on screen. The
    # icon under the still cannot answer for it; the one on the player can.
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I press Escape in the app
    And I go back to "Watchlist" in the lhs
    And I open the item "Another talk entirely"
    And I open the player's QR code
    Then the QR overlay should cover the page
    And the QR code should encode "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

  Scenario: A code left open does not come back with the next video
    # The player is mounted for the life of the page, so what it remembers
    # outlives what it is showing. Its X is above the overlay and can be
    # pressed while a code is up; the code has to go with it, or it is back
    # over the next video on its own.
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I open the player's QR code
    And I close the player
    And I click the video poster
    Then there should be exactly one player
    And the QR overlay should be gone

  Scenario: Only one code at a time
    # The player sits above the overlay on purpose, so its own QR stays under
    # the pointer while a code from the still is up. Opening it there put a
    # second #qr-overlay in the document, sharing an id with the first.
    When I open the item "A talk worth keeping"
    And I click the video poster
    # The player opens over the bottom of the lhs, and the icon under the still
    # is down there. Move it off that side first — the collision is real and
    # dragging is the answer to it, but it is not what this scenario is about.
    And I move the player out of the way
    And I open the QR code
    Then the player should not offer its QR code
    And there should be exactly one QR overlay
