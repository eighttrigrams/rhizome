Feature: Filtering related items of a focused context

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    And I type "Sapiens" in the search input
    And I press the "Enter" key
    And I press the "i" key
    And I type "1984" in the search input
    And I press the "Enter" key

  Scenario: Both items are listed and the search mode reads Normal
    Then I should see "Sapiens" in the rhs
    And I should see "1984" in the rhs
    And I should see "Normal" in the lhs

  Scenario: Typing in item search filters the related items by title
    When I press the "i" key
    And I type "Sap" in the search input
    Then I should see "Sapiens" in the rhs
    And I should not see "1984" in the rhs

  Scenario: Escape after typing restores the unfiltered list
    When I press the "i" key
    And I type "Sap" in the search input
    And I press the "Escape" key
    Then I should see "Sapiens" in the rhs
    And I should see "1984" in the rhs

  Scenario: Cycling search mode once advances the label to Reverse
    When I press the "s" key
    Then I should see "Reverse" in the lhs

  Scenario: Cycling search mode twice advances the label to 0->9
    When I press the "s" key
    And I press the "s" key
    Then I should see "0->9" in the lhs

  Scenario: Cycling search mode six times wraps back to Normal
    When I press the "s" key
    And I press the "s" key
    And I press the "s" key
    And I press the "s" key
    And I press the "s" key
    And I press the "s" key
    Then I should see "Normal" in the lhs

  Scenario: Selecting a secondary context narrows the related items
    When I press the "Escape" key
    And I press the "c" key
    And I type "Fiction" in the search input
    And I press the "Enter" key
    And I press the "Escape" key
    And I press the "a" key
    And I type "1984" in the search input
    And I press the "Enter" key
    And I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    And I click "Fiction" in the lhs
    Then I should see "1984" in the rhs
    And I should not see "Sapiens" in the rhs

  Scenario: Selecting "No secondary contexts" only keeps items without other contexts
    When I press the "Escape" key
    And I press the "c" key
    And I type "Fiction" in the search input
    And I press the "Enter" key
    And I press the "Escape" key
    And I press the "a" key
    And I type "1984" in the search input
    And I press the "Enter" key
    And I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    And I click "No secondary contexts" in the lhs
    Then I should see "Sapiens" in the rhs
    And I should not see "1984" in the rhs
