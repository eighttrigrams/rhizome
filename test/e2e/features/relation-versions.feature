Feature: The versions of the text a relation carries

  A relation's text is versioned the way an item's description is: the text that
  is replaced is kept, and the one standing now can be told apart from the ones
  before it. An item's versions are stepped through on the bar over its
  description; a relation's are stepped through in the modal that edits it, which
  is the only place the edge is on screen at all.

  The bar carries the two things the item's bar carries, and they answer two
  different questions. Left is about a version -- step back and read which one is
  on screen and where it came from. Right is about the relation as such -- who
  wrote each line of the text that is standing, whichever version the arrows are
  pointing at.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key

  Scenario: An edge nobody has written on has one version, and it is the current one
    When I open the relation modal on "Chapter one"
    Then the relation version bar should read "Version 1 (current)"
    And there should be no earlier relation version to step back to

  Scenario: The text that was replaced is still there to read
    When I open the relation modal on "Chapter one"
    And I type "why this chapter is in this book" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I type "why it is really in this book" into the relation text
    And I save the relation modal
    Then the modal should be closed
    When I open the relation modal on "Chapter one"
    Then the relation version bar should read "Version 2 (current) · app"
    And the relation text should read "why it is really in this book"
    When I step back a relation version
    Then the relation version bar should read "Version 1 · app"
    # Rendered rather than editable, which is what an older version of an item's
    # description is too. There is no editor on screen at all here, and that is
    # the point: reading a past version must not be able to become a save of it.
    And the older relation version should read "why this chapter is in this book"
    And the relation text editor should be gone
    When I step forward a relation version
    Then the relation version bar should read "Version 2 (current) · app"
    And the relation text should read "why it is really in this book"

  Scenario: Saving from an older version writes the text that is standing, not the one being read
    When I open the relation modal on "Chapter one"
    And I type "the first thing said" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I type "the second thing said" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I step back a relation version
    Then the older relation version should read "the first thing said"
    When I save the relation modal
    Then the modal should be closed
    When I open the relation modal on "Chapter one"
    Then the relation text should read "the second thing said"
    And the relation version bar should read "Version 2 (current) · app"

  Scenario: An edit in the editor survives a look at an older version
    # CodeMirror owns its own document and stepping off the current version
    # unmounts it, so this is the one gesture that could silently drop what was
    # typed.
    When I open the relation modal on "Chapter one"
    And I type "the first text" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I type "the second text" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I type "typed but not saved yet" into the relation text
    And I step back a relation version
    Then the older relation version should read "the first text"
    When I step forward a relation version
    Then the relation text should read "typed but not saved yet"

  Scenario: Ticking a badge is not a new version of the text
    When I open the relation modal on "Chapter one"
    And I type "written once" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I untick the badge on the relation
    And I save the relation modal
    Then the modal should be closed
    When I open the relation modal on "Chapter one"
    Then the relation version bar should read "Version 1 (current) · app"
    And there should be no earlier relation version to step back to

  Scenario: Provenance attributes the lines of the text that is standing
    When I open the relation modal on "Chapter one"
    And I type "a line the owner typed himself" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I open the relation's provenance
    Then the relation provenance should carry a legend
    And the relation provenance line "a line the owner typed himself" should be attributed "1.00"
    And the relation text editor should be gone
    When I close the relation's provenance
    Then the relation text should read "a line the owner typed himself"

  Scenario: An edge with nothing written on it has nothing to attribute
    When I open the relation modal on "Chapter one"
    And I open the relation's provenance
    Then the relation provenance should say there is nothing to attribute

  # Deletion is a tombstoning: the text the edge was carrying goes to the history
  # on the way out, marked. For a relation that mark earns its keep, because the
  # history is keyed on the two items and an edge can come back — so an unlink and
  # a re-link leave one version list with the cut in the middle of it, and the mark
  # is what says that is what happened rather than a text having been blanked.
  Scenario: An edge that was unlinked and made again says where it was cut
    When I open the relation modal on "Chapter one"
    And I type "why this chapter is in this book" into the relation text
    And I save the relation modal
    # A second container, or the unlink is refused — see unlink.feature. Filed
    # through the app rather than over the API, because the refusal branches on
    # the containers the CLIENT is holding for that row, and coming back into Book
    # afterwards is what re-reads them.
    And I press the "Escape" key
    And I press the "c" key
    And I type "Shelf" in the search input
    And I press the "Enter" key
    And I press the "Escape" key
    And I press the "a" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key
    And I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I unlink the row "Chapter one" from the list
    Then I should not see "Chapter one" in the rhs
    # And made again, by the gesture that made it the first time.
    When I press the "Escape" key
    And I press the "a" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key
    Then I should see "Chapter one" in the rhs
    When I open the relation modal on "Chapter one"
    # Nothing is written on the edge that came back, and it opens on that rather
    # than on the text from before: what the edge carries now is nothing.
    Then the relation version bar should read "Version 2 (current)"
    When I step back a relation version
    Then the relation version bar should read "Version 1 · app · unlinked"
    And the older relation version should read "why this chapter is in this book"
    And the older relation version should say the relation was unlinked here
