Feature: Marking a relation as part-of in the edit modal

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key

  Scenario: The checkbox and the sibling index round-trip through a save
    When I select the item "Chapter one"
    And I open the edit modal
    And I mark the relation to "Book" as part of, at index "3"
    And I save the modal
    Then the modal should be closed
    When I open the edit modal
    Then the relation to "Book" should be marked as part of, at index "3"

  Scenario: A sibling index that is not a number is stored as unplaced
    When I select the item "Chapter one"
    And I open the edit modal
    And I mark the relation to "Book" as part of, at index "no such number"
    And I save the modal
    Then the modal should be closed
    When I open the edit modal
    Then the relation to "Book" should be marked as part of, at index ""

  Scenario: A refused save keeps the modal, the message, and everything typed in it
    When I make "Chapter one" part of "Book" at index 1
    And I link "Book" under "Chapter one"
    And I reload the app
    And I select the context "Book"
    And I open the edit modal
    And I mark the relation to "Chapter one" as part of, at index "1"
    And I type "Book, renamed" into the title field
    And I save the modal
    Then the modal should still be open
    And the modal should say the save was refused, naming "Chapter one"
    And the modal should say nothing was saved
    And the title field should still read "Book, renamed"
    And the relation to "Chapter one" should be marked as part of, at index "1"
    And the item "Book" should still be titled "Book"
