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

  Scenario: Escape from context search creates nothing
    Given I am on the app
    When I press the "c" key
    And I type "Books" in the search input
    And I press the "Escape" key
    Then I should not see "Books" in the lhs

  Scenario: Escape from item search creates nothing
    Given I am on the app
    When I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    And I type "Sapiens" in the search input
    And I press the "Escape" key
    Then I should not see "Sapiens" in the rhs

  Scenario: Pressing i reopens item search after item creation
    Given I am on the app
    When I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    And I type "Sapiens" in the search input
    And I press the "Enter" key
    And I press the "i" key
    Then I should see the search input
