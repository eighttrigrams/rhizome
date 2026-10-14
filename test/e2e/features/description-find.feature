Feature: Finding in the description

  The description editor answers to the owner's VSCode find, the same chords
  zoo's editor answers to: ⌘ä opens the find box -- ⌘' on a US layout, it is
  the physical key that counts -- and ⌘. and ⌘, go to the next and the previous
  match. Escape with the box open closes the box and nothing more; the item is
  left by the Escape after it.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Finding" in the search input
    And I press the "Enter" key
    Then I should see "Finding" in the lhs
    And "Finding" holds an item "The transcript" described as "genes one\nmemes two\ngenes three\n"
    When I reload the app
    And I select the item "The transcript"
    And I open the description

  Scenario: ⌘ä opens the find box, and ⌘. and ⌘, go from match to match
    When I press "Meta+Quote" in the description
    Then the find box should be open
    When I type "genes" in the find box
    And I press "Meta+Period" in the description
    Then the match on line 1 should be selected
    When I press "Meta+Period" in the description
    Then the match on line 3 should be selected
    When I press "Meta+Comma" in the description
    Then the match on line 1 should be selected

  Scenario: Escape closes the find box first, and the item only after that
    When I press "Meta+Quote" in the description
    And I type "genes" in the find box
    And I press "Escape" in the description
    Then the find box should be closed
    And the description should still be open
    When I press "Escape" in the description
    Then the description should be closed
