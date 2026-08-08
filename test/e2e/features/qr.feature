Feature: Carrying a video to a phone by QR code

  The item detail view offers a video's address as a QR code over the whole
  page. The address is the one YouTube serves, not the embed/ form the iframe
  is built from — a phone that lands on the latter gets a bare player.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Watchlist" in the search input
    And I press the "Enter" key
    And I type "A talk worth keeping" in the search input
    And I press the "Enter" key
    And the item "A talk worth keeping" has "https://www.youtube.com/watch?v=dQw4w9WgXcQ" in its description
    And I reload the app

  Scenario: The icon opens a QR code for the video's real address
    When I open the item "A talk worth keeping"
    Then the video should offer a QR code
    When I open the QR code
    Then the QR overlay should cover the page
    And the QR code should encode "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    And the QR code should not encode the embed address

  Scenario: The X closes it
    When I open the item "A talk worth keeping"
    And I open the QR code
    And I click the QR overlay's close button
    Then the QR overlay should be gone

  Scenario: Escape closes it, and does not reach the app underneath
    When I open the item "A talk worth keeping"
    And I open the QR code
    And I press Escape in the QR overlay
    Then the QR overlay should be gone
    # Escape in the item view means "leave the item view". If the keypress that
    # closed the overlay had fallen through, that would already have happened.
    And the item view should still be open
    # And the app has the keyboard back: the next Escape is the one that acts.
    When I press Escape in the app
    Then the item view should be closed

  Scenario: The preview is not offered the icon
    # The same component renders under the pointer while hovering a row. The
    # video shows there; the way out to a phone does not.
    When I hover the item "A talk worth keeping"
    Then the preview should show the video
    And the video should not offer a QR code
