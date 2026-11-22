Feature: Documents, sounds and videos open in the app

  The green badge opens every file in the app's viewer, not only a picture: a
  pdf in the browser's own pdf viewer, a sound and a video in a player, and
  anything the browser cannot show with a word that it cannot, and the file to
  download. They are served from /files/, which answers the Range requests a
  player seeks with. Back and forth step through the files of the kind that is
  open. Option+click still sends a file out to the system's viewer.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Shelf" in the search input
    And I press the "Enter" key
    Then I should see "Shelf" in the lhs
    And "Shelf" holds an item "Letter" with the file "e2e-tax letter #2.pdf"
    And "Shelf" holds an item "Chime" with the file "e2e-chime.mp3"
    And "Shelf" holds an item "Harbour film" with the file "e2e-harbour.mp4"
    And "Shelf" holds an item "Old film" with the file "e2e-old.flv"
    And "Shelf" holds an item "Bell" with the file "e2e-bell.mp3"
    When I reload the app
    And I watch the files fetched

  Scenario: A pdf opens in the browser's own pdf viewer
    When I click the green badge of "Letter"
    Then the viewer should show the pdf "e2e-tax letter #2.pdf"
    And "e2e-tax letter #2.pdf" should have come as "application/pdf"

  Scenario: A sound plays in the app, and can be sought in
    When I click the green badge of "Chime"
    Then the viewer should show the audio "e2e-chime.mp3"
    And the player should be ready, 1 second long
    When I seek the player to 0.5 seconds
    Then the player should be at 0.5 seconds

  Scenario: A video plays in the app, and can be sought in
    When I click the green badge of "Harbour film"
    Then the viewer should show the video "e2e-harbour.mp4"
    And the player should be ready, 1 second long
    And the video should be 64 by 48
    And "e2e-harbour.mp4" should have been fetched in a range
    When I seek the player to 1 seconds
    Then the player should be at 1 seconds

  Scenario: What the browser cannot show, it says so and offers to download
    When I click the green badge of "Old film"
    Then the viewer should say "This file cannot be shown here."
    And the viewer should offer "e2e-old.flv" to download

  Scenario: Download sends the file as an attachment
    When I click the green badge of "Letter"
    And I click the download button
    Then "e2e-tax letter #2.pdf" should have been downloaded

  Scenario: Back and forth stay within the kind that is open
    # Newest first: Bell, Old film, Harbour film, Chime, Letter. From Bell the
    # next sound is Chime, past the video and the flv.
    When I click the green badge of "Bell"
    Then the viewer should be at file 1 of 2, "Bell"
    When I press "ArrowRight" in the viewer
    Then the viewer should be at file 2 of 2, "Chime"
    And the viewer should show the audio "e2e-chime.mp3"
    And the next button should be disabled

  Scenario: Option+click still sends a video out
    When I Option+click the green badge of "Harbour film"
    Then the file "e2e-harbour.mp4" should have been sent to the system's viewer
    And the image viewer should be gone
