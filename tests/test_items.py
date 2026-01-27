"""Integration tests for the items endpoint via PostgREST."""

import requests

BASE_URL = "http://localhost:3000"

def test_test():
    assert(1 == 1)


def test_item_lifecycle():
    """Verify the full create -> read -> delete -> confirm-deleted lifecycle."""

    # First create a list, since items now require a parent list.
    resp = requests.post(
        f"{BASE_URL}/lists",
        json={"name": "Test list", "description": "A test list"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    list_id = resp.json()[0]["id"]

    # Create a new item in the list via POST.
    # "Prefer: return=representation" asks PostgREST to return the created row.
    resp = requests.post(
        f"{BASE_URL}/items",
        json={"list_id": list_id, "title": "Test item", "description": "A test description"},
        headers={"Prefer": "return=representation"},
    )
    assert resp.status_code == 201
    item = resp.json()[0]
    assert item["title"] == "Test item"
    assert item["description"] == "A test description"
    assert item["done"] is False
    assert item["list_id"] == list_id
    item_id = item["id"]

    # Fetch the item by ID and verify it exists.
    # PostgREST uses "id=eq.{value}" syntax for equality filtering.
    resp = requests.get(f"{BASE_URL}/items?id=eq.{item_id}")
    assert resp.status_code == 200
    items = resp.json()
    assert len(items) == 1
    assert items[0]["title"] == "Test item"

    # Delete the item by ID.
    resp = requests.delete(f"{BASE_URL}/items?id=eq.{item_id}")
    assert resp.status_code == 204

    # Confirm the item no longer exists.
    resp = requests.get(f"{BASE_URL}/items?id=eq.{item_id}")
    assert resp.status_code == 200
    assert resp.json() == []

    # Clean up the list.
    resp = requests.delete(f"{BASE_URL}/lists?id=eq.{list_id}")
    assert resp.status_code == 204
