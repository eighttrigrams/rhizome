Feature: Editing one relation from the card it is shown on

  The card in the list stands for one edge -- the item, under the whole it is
  filed under. Clicking its annotation strip opens that edge, and everything the
  edge carries is on offer there: the two annotations, the badge, and the
  part-of standing. Until now only the annotations were, and the rest could be
  reached only from the edit modal of the item itself.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key

  Scenario: The part-of standing round-trips through the relation modal
    When I open the relation modal on "Chapter one"
    Then the relation modal should be titled "Edit Relation"
    When I mark the relation as part of, at index "3"
    And I save the relation modal
    Then the modal should be closed
    When I open the relation modal on "Chapter one"
    Then the relation should be marked as part of, at index "3"
    When I close the relation modal
    And I press the "h" key with shift and alt
    Then the rhs should list exactly "Chapter one"

  Scenario: Unticking the badge takes it off the card in the other context
    When I create the context "Shelf"
    And I link "Chapter one" under "Shelf"
    And I reload the app
    And I select the context "Shelf"
    Then the card for "Chapter one" should carry a badge for "Book"
    When I reload the app
    And I select the context "Book"
    And I open the relation modal on "Chapter one"
    And I untick the badge on the relation
    And I save the relation modal
    Then the modal should be closed
    When I reload the app
    And I select the context "Shelf"
    Then the card for "Chapter one" should not carry a badge for "Book"

  Scenario: A refused part-of tick keeps the modal, the message, and what was typed
    When I make "Book" part of "Chapter one" at index 1
    And I reload the app
    And I select the context "Book"
    And I open the relation modal on "Chapter one"
    And I mark the relation as part of, at index "1"
    And I type "typed but never saved" into the relation annotation field
    And I save the relation modal
    Then the modal should still be open
    And the modal should say the save was refused, naming "Chapter one"
    And the modal should say nothing was saved
    And the relation annotation field should still read "typed but never saved"
    And the relation should be marked as part of, at index "1"
