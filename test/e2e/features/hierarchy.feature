Feature: Hierarchy mode

  Scenario: Hierarchy mode shows the context's parts, in sibling order, and hides the rest
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter two" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Chapter unplaced" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Merely related" in the search input
    And I press the "Enter" key
    And I make "Chapter two" part of "Book" at index 2
    And I make "Chapter one" part of "Book" at index 1
    And I make "Chapter unplaced" part of "Book" with no index
    And I press the "h" key with shift and alt
    Then I should see "Hierarchy mode" in the top strip
    And the top strip should push the app down
    And I should not see "Search mode" in the lhs
    And the rhs should list exactly "Chapter one, Chapter two, Chapter unplaced"

  Scenario: Leaving hierarchy mode brings the whole related-items list back
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Merely related" in the search input
    And I press the "Enter" key
    And I make "Chapter one" part of "Book" at index 1
    And I press the "h" key with shift and alt
    And I press the "h" key with shift and alt
    Then I should not see the top strip
    And I should see "Merely related" in the rhs
    And I should see "Search mode" in the lhs

  Scenario: Danger mode does not offer its bulk delete in hierarchy mode
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key
    And I press the "d" key with shift and alt
    Then the bulk delete button should be enabled
    When I press the "h" key with shift and alt
    Then the bulk delete button should be disabled

  Scenario: Entering vector search leaves hierarchy mode
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key
    And I press the "h" key with shift and alt
    Then I should see "Hierarchy mode" in the top strip
    When I press the "i" key
    And I press the "v" key with shift and alt in the search input
    Then I should not see the top strip
    And the search input should be in vector mode

  Scenario: The strip steps to the level below, and stops where the hierarchy stops
    Given I am on the app
    When I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter one" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Chapter two" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Page of the first chapter" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Page of the second chapter" in the search input
    And I press the "Enter" key
    And I make "Chapter one" part of "Book" at index 1
    And I make "Chapter two" part of "Book" at index 2
    And I make "Page of the first chapter" part of "Chapter one" at index 1
    And I make "Page of the second chapter" part of "Chapter two" at index 1
    And I press the "h" key with shift and alt
    Then the strip should show level 1
    And the rhs should list exactly "Chapter one, Chapter two"
    And the strip should not offer to step down
    And the strip should offer to step up
    When I step the level up
    Then the strip should show level 2
    And the rhs should list exactly "Page of the first chapter, Page of the second chapter"
    And the strip should not offer to step up
    When I step the level down
    Then the strip should show level 1
    And the rhs should list exactly "Chapter one, Chapter two"

  Scenario: The level goes back to 1 when another context is selected
    Given I am on the app
    When I press the "c" key
    And I type "Folio" in the search input
    And I press the "Enter" key
    And I press the "Escape" key
    And I press the "Escape" key
    And I press the "c" key
    And I type "Book" in the search input
    And I press the "Enter" key
    And I type "Chapter" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "Page" in the search input
    And I press the "Enter" key
    And I make "Chapter" part of "Book" at index 1
    And I make "Page" part of "Chapter" at index 1
    And I press the "h" key with shift and alt
    And I step the level up
    Then the strip should show level 2
    And the rhs should list exactly "Page"
    When I press the "c" key
    And I type "Folio" in the search input
    And I press the "Enter" key
    Then the strip should show level 1
    And the strip should not offer to step up

  Scenario: The badges sit below the strip rather than on top of its label
    Given I am on the app
    When I press the "w" key with shift and alt
    And I press the "h" key with shift and alt
    Then I should see "Hierarchy mode" in the top strip
    And no badge should overlap the top strip
