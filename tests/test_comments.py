"""Integration tests for the comments endpoints via PostgREST."""

import requests

BASE_URL = "http://localhost:3000"


def test_seed_comments_exist():
    """Verify that seed data comments are present."""
    # Get comments for the Milk item (item_id=1 in seed data)
    resp = requests.get(f"{BASE_URL}/items?title=eq.Milk")
    assert resp.status_code == 200
    items = resp.json()
    assert len(items) > 0
    milk_id = items[0]["id"]

    resp = requests.get(f"{BASE_URL}/comments?item_id=eq.{milk_id}&order=created_at")
    assert resp.status_code == 200
    comments = resp.json()
    assert len(comments) >= 2

    authors = [c["author_name"] for c in comments]
    assert "Alice" in authors
    assert "Bob" in authors


def test_comment_lifecycle():
    """Verify the create -> read lifecycle for comments.

    Note: Direct deletion of comments is restricted by RLS to the comment author.
    Comments without author_id (anonymous) can only be deleted via cascade.
    """
    # First create a list and item
    resp = requests.post(
        f"{BASE_URL}/lists",
        json={"name": "Test comment list", "description": "For comment testing"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    list_id = resp.json()[0]["id"]

    resp = requests.post(
        f"{BASE_URL}/items",
        json={"list_id": list_id, "title": "Test item for comments"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    item_id = resp.json()[0]["id"]

    # Create a comment (without author_id - anonymous)
    resp = requests.post(
        f"{BASE_URL}/comments",
        json={
            "item_id": item_id,
            "author_name": "TestUser",
            "content": "This is a test comment",
        },
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    comment = resp.json()[0]
    assert comment["item_id"] == item_id
    assert comment["author_name"] == "TestUser"
    assert comment["content"] == "This is a test comment"
    assert comment["author_id"] is None  # Anonymous comment
    assert "created_at" in comment
    comment_id = comment["id"]

    # Fetch the comment
    resp = requests.get(f"{BASE_URL}/comments?id=eq.{comment_id}")
    assert resp.status_code == 200
    comments = resp.json()
    assert len(comments) == 1
    assert comments[0]["content"] == "This is a test comment"

    # Try to delete the comment - RLS should block this (no author_id match)
    resp = requests.delete(f"{BASE_URL}/comments?id=eq.{comment_id}")
    assert resp.status_code == 204  # Returns 204 but deletes nothing

    # Comment should still exist (RLS blocked the delete)
    resp = requests.get(f"{BASE_URL}/comments?id=eq.{comment_id}")
    assert resp.status_code == 200
    assert len(resp.json()) == 1  # Still there

    # Clean up via cascade: delete item (cascades to comments) and list
    resp = requests.delete(f"{BASE_URL}/items?id=eq.{item_id}")
    assert resp.status_code == 204

    # Now comment should be gone via cascade
    resp = requests.get(f"{BASE_URL}/comments?id=eq.{comment_id}")
    assert resp.status_code == 200
    assert resp.json() == []

    resp = requests.delete(f"{BASE_URL}/lists?id=eq.{list_id}")
    assert resp.status_code == 204


def test_cascade_delete_item_removes_comments():
    """Verify that deleting an item cascades to delete its comments."""
    # Create a list
    resp = requests.post(
        f"{BASE_URL}/lists",
        json={"name": "Cascade test list"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    list_id = resp.json()[0]["id"]

    # Create an item
    resp = requests.post(
        f"{BASE_URL}/items",
        json={"list_id": list_id, "title": "Item to be deleted"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    item_id = resp.json()[0]["id"]

    # Create multiple comments on the item
    for i in range(3):
        resp = requests.post(
            f"{BASE_URL}/comments",
            json={
                "item_id": item_id,
                "author_name": f"User{i}",
                "content": f"Comment {i}",
            },
        )
        assert resp.status_code == 201

    # Verify comments exist
    resp = requests.get(f"{BASE_URL}/comments?item_id=eq.{item_id}")
    assert resp.status_code == 200
    assert len(resp.json()) == 3

    # Delete the item
    resp = requests.delete(f"{BASE_URL}/items?id=eq.{item_id}")
    assert resp.status_code == 204

    # Verify comments are gone (cascade delete)
    resp = requests.get(f"{BASE_URL}/comments?item_id=eq.{item_id}")
    assert resp.status_code == 200
    assert resp.json() == []

    # Clean up the list
    resp = requests.delete(f"{BASE_URL}/lists?id=eq.{list_id}")
    assert resp.status_code == 204


def test_cascade_delete_list_removes_comments():
    """Verify that deleting a list cascades to delete items and their comments."""
    # Create a list
    resp = requests.post(
        f"{BASE_URL}/lists",
        json={"name": "Full cascade test list"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    list_id = resp.json()[0]["id"]

    # Create an item
    resp = requests.post(
        f"{BASE_URL}/items",
        json={"list_id": list_id, "title": "Item in list to be deleted"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    item_id = resp.json()[0]["id"]

    # Create a comment
    resp = requests.post(
        f"{BASE_URL}/comments",
        json={
            "item_id": item_id,
            "author_name": "CascadeUser",
            "content": "This should be deleted when list is deleted",
        },
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    comment_id = resp.json()[0]["id"]

    # Delete the list
    resp = requests.delete(f"{BASE_URL}/lists?id=eq.{list_id}")
    assert resp.status_code == 204

    # Verify item is gone
    resp = requests.get(f"{BASE_URL}/items?id=eq.{item_id}")
    assert resp.status_code == 200
    assert resp.json() == []

    # Verify comment is gone
    resp = requests.get(f"{BASE_URL}/comments?id=eq.{comment_id}")
    assert resp.status_code == 200
    assert resp.json() == []
