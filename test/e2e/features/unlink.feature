Feature: Refusing to unlink an item from its last context

  An item that is not itself a context has to stay in at least one container.
  Unlinking it from its only one is refused, and the refusal is said out loud
  in the same banner the acyclicity refusal uses — before, the row simply
  stayed put and nothing was said, so the gesture looked broken.

  Background:
    Given I am on the app
    When I press the "c" key
    And I type "Books" in the search input
    And I press the "Enter" key
    And I type "Sapiens" in the search input
    And I press the "Enter" key

  Scenario: Unlinking a row from its only whole says why it was refused
    When I unlink the row "Sapiens" from the list
    Then the list should say the unlink was refused, naming "Sapiens" and "Books"
    And the list should say nothing was unlinked
    And I should see "Sapiens" in the rhs

  Scenario: The refusal goes away once the row has somewhere else to be
    When I unlink the row "Sapiens" from the list
    Then the list should say the unlink was refused, naming "Sapiens" and "Books"
    When I also file "Sapiens" under a context "History"
    # Going to the new context, rather than back into Books: the context search
    # leaves out the context already selected, so "Books" would filter to an
    # empty list and Enter would select nothing. Either way the row now has two
    # containers, and unlinking it from one of them is allowed.
    And I press the "c" key
    And I type "History" in the search input
    And I press the "Enter" key
    And I unlink the row "Sapiens" from the list
    # The row leaving the list is the assertion that carries this: selecting a
    # context clears the banner by itself, so its absence alone would prove
    # nothing about whether the second unlink went through.
    Then I should not see "Sapiens" in the rhs
    And the list should not say an unlink was refused
