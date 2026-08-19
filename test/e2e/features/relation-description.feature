Feature: The text a relation carries

  A relation holds a body of text of its own, edited in the modal the card's
  strip opens and read by resting the pointer on that same strip, where it takes
  the lhs over from the hovered item's preview.

  It is the one thing an edge carries that no list brings with it. That is the
  point of it: prose, one per edge, and a list is a hundred edges. So it is
  fetched when a pointer comes to rest on one, and the scenario below watches
  the wire to say so.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key

  Scenario: The text round-trips through the relation modal
    When I open the relation modal on "Chapter one"
    And I type "why this chapter is in this book" into the relation text
    And I save the relation modal
    Then the modal should be closed
    When I open the relation modal on "Chapter one"
    Then the relation text should read "why this chapter is in this book"

  Scenario: Clearing it is a text the user removed, and it stays cleared
    When I open the relation modal on "Chapter one"
    And I type "written by mistake" into the relation text
    And I save the relation modal
    And I open the relation modal on "Chapter one"
    And I type "" into the relation text
    And I save the relation modal
    Then the modal should be closed
    When I open the relation modal on "Chapter one"
    Then the relation text should read ""

  Scenario: Resting on the strip shows the text, and nothing before that asks for it
    When I open the relation modal on "Chapter one"
    And I type "why this chapter is in this book" into the relation text
    And I save the relation modal
    And I reload the app
    And I select the context "Book"
    And I watch the calls to the server
    Then no relation text should have been fetched
    When I hover the relation strip on "Chapter one"
    Then the lhs should show the relation text "why this chapter is in this book"
    And the relation text should have been fetched once

  Scenario: Leaving the strip gives the item's own preview back
    When I open the relation modal on "Chapter one"
    And I type "the relation's own text" into the relation text
    And I save the relation modal
    And I reload the app
    And I select the context "Book"
    And I hover the relation strip on "Chapter one"
    Then the lhs should show the relation text "the relation's own text"
    When I hover the body of the card "Chapter one"
    Then the lhs should show no relation text

  Scenario: An edge nobody has written on says so rather than showing nothing
    When I hover the relation strip on "Chapter one"
    Then the lhs should say the relation has no text yet
