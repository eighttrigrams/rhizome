Feature: Who wrote each line of the description

  The version bar over a description says where each whole VERSION came from.
  Provenance says something else, about the same item: of the text as it reads
  right now, which stretches are the owner's own hand and which an agent may
  rewrite. An item he wrote once and an agent has edited nineteen times since
  still has his paragraph marked as his here, and a list of nineteen agent
  versions would never say so.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Provenance" in the search input
    And I press the "Enter" key
    Then I should see "Provenance" in the lhs
    # Both items over REST, the way the player scenarios set theirs up: one
    # creation through the search input is reliable, a second chained behind it
    # is not. POST /api/items stamps its write "api", which is precisely what
    # makes these an agent's lines rather than his.
    #
    # Both bodies END IN A NEWLINE, deliberately. us-vs-them counts that
    # trailing empty line and clojure.string/split-lines throws it away, so a
    # client that splits the wrong way draws one row too few -- at the bottom,
    # in silence, with every other row present and correctly tinted. A fixture
    # whose body did not end in a newline could not fail that way, and the
    # check below would be green for a page that had the bug.
    And "Provenance" holds an item "The long note" described as "An agent's opening line.\nAnd a second one.\n"
    And "Provenance" holds an item "Another note" described as "Something else entirely.\n"
    When I reload the app

  Scenario: The page draws the source text, one row per line, as the API reads it
    When I select the item "The long note"
    And I open the provenance page
    Then the provenance page should agree with the API about "The long note"

  Scenario: The last line is drawn even though the body ends in a newline
    When I select the item "The long note"
    And I open the provenance page
    Then the provenance page should have one row per line of "The long note"

  Scenario: His lines and an agent's are told apart within one description
    When I select the item "The long note"
    And the owner himself adds "A line the owner typed himself." to the description
    And I open the provenance page
    # The whole answer, checked against the server's, before anything is said
    # about individual lines.
    Then the provenance page should agree with the API about "The long note"
    And the line reading "A line the owner typed himself." should be attributed "1.00"
    And the line reading "An agent's opening line." should be attributed "0.00"
    # Colour is the thing a reader actually sees, and it is computed from the
    # number rather than read off a class, so the two can come apart.
    And those two lines should not be tinted alike

  Scenario: The legend on the page is the one the API serves
    When I select the item "The long note"
    And I open the provenance page
    Then the page should carry the API's own legend for "The long note"

  Scenario: Provenance opens on the current description, whatever version the bar shows
    # The bar is about a version and this is not, so stepping the bar back must
    # not change the text under the tints.
    When I select the item "The long note"
    And the owner himself adds "A line the owner typed himself." to the description
    And I step the version bar back one version
    Then the version bar should not be showing the current version
    When I open the provenance page
    Then the provenance page should agree with the API about "The long note"
    And the provenance page should show the line "A line the owner typed himself."

  Scenario: It closes, and the next item's page is the next item's
    When I select the item "The long note"
    And I open the provenance page
    And I close the provenance page
    Then the provenance page should be gone
    # Escape first: selecting an item puts the lhs in item view, and the context
    # badge that leads back to the whole is only drawn once that is closed.
    When I press Escape in the app
    And I go back to "Provenance" in the lhs
    And I select the item "Another note"
    And I open the provenance page
    Then the provenance page should agree with the API about "Another note"
    And the provenance page should show the line "Something else entirely."
    And the provenance page should not show the line "An agent's opening line."
