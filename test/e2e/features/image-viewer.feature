Feature: Images open in the app

  The green badge of an item with an image file opens the image over nearly the
  whole window, instead of in the system's own viewer. Option+click still sends
  it out there, and any other kind of file always goes out. In the viewer, back
  and forth step through the images of the list as it is on screen, in its
  order, and pass over the items whose file is not an image.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Album" in the search input
    And I press the "Enter" key
    Then I should see "Album" in the lhs
    # Over REST and then straight into the e2e database: no route sets an
    # item's file, which in the app only an import does.
    And "Album" holds an item "Harbour at dawn" with the file "e2e-harbour.jpg"
    And "Album" holds an item "Tax letter" with the file "e2e-tax-letter.pdf"
    And "Album" holds an item "Lighthouse" with the file "e2e-lighthouse.png"
    And "Album" holds an item "Cliffs" with the file "e2e-cliffs.webp"
    When I reload the app

  Scenario: A plain click on the green badge opens the image in the app
    When I click the green badge of "Lighthouse"
    Then the image viewer should show "e2e-lighthouse.png"
    And the image should have loaded
    And the image viewer should cover nearly the whole window

  Scenario: Back and forth go over the images only, in list order, and stop at the ends
    # Newest first. The pdf sits between the second and third image, and the
    # context's own card comes below them all -- neither has an image to show.
    Then the list should read "Cliffs, Lighthouse, Tax letter, Harbour at dawn, Album"
    When I click the green badge of "Cliffs"
    Then the viewer should be at image 1 of 3, "Cliffs"
    And the previous button should be disabled
    When I press "ArrowRight" in the viewer
    Then the viewer should be at image 2 of 3, "Lighthouse"
    When I click the next button
    Then the viewer should be at image 3 of 3, "Harbour at dawn"
    And the next button should be disabled
    And the viewer should not have shown "e2e-tax-letter.pdf"
    When I press "ArrowRight" in the viewer
    Then the viewer should be at image 3 of 3, "Harbour at dawn"
    When I press "ArrowLeft" in the viewer
    Then the viewer should be at image 2 of 3, "Lighthouse"

  Scenario: Escape closes it, and the app has its keys back
    When I click the green badge of "Lighthouse"
    And I press "Escape" in the viewer
    Then the image viewer should be gone
    And the app should have the focus

  Scenario: Keys in the viewer do not reach the app behind it
    # "c" is a shortcut on #main-layer (new context); Tab would walk the focus
    # out to controls behind the overlay that nobody can see.
    When I click the green badge of "Lighthouse"
    And I press "c" in the viewer
    And I press "Tab" in the viewer
    Then the focus should still be in the image viewer
    And the app behind the viewer should be as it was

  Scenario: A click beside the image closes it
    When I click the green badge of "Lighthouse"
    And I click the viewer's backdrop
    Then the image viewer should be gone

  Scenario: Option+click sends the image out, as every click used to
    When I Option+click the green badge of "Lighthouse"
    Then the file "e2e-lighthouse.png" should have been sent to the system's viewer
    And the image viewer should be gone

  Scenario: A file that is not an image goes out on a plain click
    When I click the green badge of "Tax letter"
    Then the file "e2e-tax-letter.pdf" should have been sent to the system's viewer
    And the image viewer should be gone

  Scenario: A name with a space and a # survives both ways
    And "Album" holds an item "Night swim" with the file "e2e-night swim #2.png"
    When I reload the app
    And I click the green badge of "Night swim"
    Then the image viewer should show "e2e-night swim #2.png"
    And the image should have loaded
    When I press "Escape" in the viewer
    And I Option+click the green badge of "Night swim"
    Then the file "e2e-night swim #2.png" should have been sent to the system's viewer

  Scenario: Opened from the left-hand card, it shows the picture alone
    # A context with a file of its own, selected: its card is on the left, and
    # the list on the right is its related items, which it is not one of. There
    # is nowhere to step from, so there is nothing offered to step with.
    And "Lighthouse" is a context
    When I reload the app
    And I press the "c" key
    And I type "Lighthouse" in the search input
    And I select the context "Lighthouse"
    And I click the green badge of "Lighthouse" in the lhs
    Then the image viewer should show "e2e-lighthouse.png"
    And the viewer should show the title "Lighthouse"
    And the viewer should offer no stepping

  Scenario: An image filed under two chapters is stepped through at both places
    # Hierarchy mode lists a node once per path, so one item can stand twice in
    # one list. Each place is a stop of its own, and the second is no way back
    # to the first.
    And "Album" holds an item "Chapter one" with no file
    And "Album" holds an item "Chapter two" with no file
    And I make "Chapter one" part of "Album" at index 1
    And I make "Chapter two" part of "Album" at index 2
    And I make "Lighthouse" part of "Chapter one" at index 1
    And I make "Cliffs" part of "Chapter two" at index 1
    And I make "Lighthouse" part of "Chapter two" at index 2
    And I make "Harbour at dawn" part of "Chapter two" at index 3
    When I reload the app
    And I open the context "Album" from the list
    And I press the "h" key with shift and alt
    And I step the level up
    Then the rhs should list exactly "Lighthouse, Cliffs, Lighthouse, Harbour at dawn"
    When I click the green badge of "Lighthouse"
    Then the viewer should be at image 1 of 4, "Lighthouse"
    When I press "ArrowRight" in the viewer
    And I press "ArrowRight" in the viewer
    Then the viewer should be at image 3 of 4, "Lighthouse"
    When I press "ArrowRight" in the viewer
    Then the viewer should be at image 4 of 4, "Harbour at dawn"
    When I press "Escape" in the viewer
    And I click the green badge of the 2nd "Lighthouse"
    Then the viewer should be at image 3 of 4, "Lighthouse"

  Scenario: A code opened from the player over the viewer gives the keys back to the viewer
    # The floating player sits above the viewer on purpose, and its QR overlay
    # hands the focus to #main-layer when it closes. With the viewer still up,
    # that would put the hidden app's shortcuts under the keyboard.
    And "Album" holds an item "A talk worth keeping" showing "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    When I reload the app
    And I open the item "A talk worth keeping"
    And I click the video poster
    And I press Escape in the app
    And I go back to "Album" in the lhs
    And I click the green badge of "Lighthouse"
    And I open the player's QR code
    Then the QR code should be in front of the image viewer
    When I press "Escape" in the viewer
    Then the QR overlay should be gone
    And the focus should still be in the image viewer
    When I press "c" in the viewer
    Then the focus should still be in the image viewer
    And the app behind the viewer should be as it was
