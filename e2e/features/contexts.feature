Feature: Contexts and items

  Scenario: User creates a context
    Given I am on the app
    When I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    Then I should see "Books" in the lhs

  Scenario: User adds an item to a freshly created context
    Given I am on the app
    When I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    And I type "Sapiens" in the search input
    And I press the "Enter" key
    Then I should see "Sapiens" in the rhs
