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
    And I type "A talk worth keeping" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Another talk entirely" in the search input
    And I press the "Enter" key
    And the item "A talk worth keeping" has "https://www.youtube.com/watch?v=dQw4w9WgXcQ" in its description
    And the item "Another talk entirely" has "https://www.youtube.com/watch?v=oHg5SJYRHA0" in its description
    And I reload the app

  Scenario: A still stands where the embed used to be, and nothing plays there
    When I open the item "A talk worth keeping"
    Then the item should show a still for "dQw4w9WgXcQ"
    And nothing should be playing in the item view
    And nothing should be playing

  Scenario: Clicking the still starts the player, playing, in the top-left corner
    When I open the item "A talk worth keeping"
    And I click the video poster
    Then the player should be playing "dQw4w9WgXcQ"
    # A player that needs a second click is not what was asked for. Both halves
    # are needed: the URL asks to start, and the frame has to be allowed to.
    And the player should be asked to start on its own
    And the player should be in the "top-left" corner

  Scenario: Moving to another item leaves the player playing
    # The bug this is written against is a remount. An iframe React rebuilds,
    # or that is re-parented, reloads and plays from zero — and "an iframe is
    # present" passes on exactly that, so the node itself is held onto and
    # compared. Every hop below is a real navigation, not a redraw.
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I remember the player's iframe
    # It comes up in the top-left, over the card of the very item it was
    # started from — that collision is the reason it is draggable at all, and
    # moving it aside is what the owner does before going anywhere.
    And I move the player out of the way
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
    And I move the player out of the way
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
    And I move the player out of the way
    And I press Escape in the app
    And I go back to "Watchlist" in the lhs
    And I open the item "Another talk entirely"
    And I open the player's QR code
    Then the QR overlay should cover the page
    And the QR code should encode "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

  Scenario: Only one code at a time
    # The player sits above the overlay on purpose, so its own QR stays under
    # the pointer while a code from the still is up. Opening it there put a
    # second #qr-overlay in the document, sharing an id with the first.
    When I open the item "A talk worth keeping"
    And I click the video poster
    And I open the QR code
    Then the player should not offer its QR code
    And there should be exactly one QR overlay
