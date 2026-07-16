"""
Verification: does sqlite-vec support a single SQL statement that combines
a structured (WHERE) filter with a vector similarity ORDER BY?

This directly answers vision.md section 8, verification item #2:
"Whether the chosen SQLite vector extension supports combined structured +
similarity queries in one statement."

Scenario modeled on vision.md section 8's own example:
  "events where participant = Rahul, ordered by similarity to 'document discussion'"
"""
import sqlite3
import sqlite_vec
import numpy as np
import struct


def serialize_f32(vector):
    return struct.pack(f"{len(vector)}f", *vector)


def main():
    db = sqlite3.connect(":memory:")
    db.enable_load_extension(True)
    sqlite_vec.load(db)
    db.enable_load_extension(False)

    print("sqlite_vec version:", db.execute("select vec_version()").fetchone()[0])
    print("sqlite version:", sqlite3.sqlite_version)

    # --- Structured side: a normal relational table (contacts / events) ---
    db.execute("""
        CREATE TABLE events (
            event_id INTEGER PRIMARY KEY,
            participant TEXT NOT NULL,
            title TEXT NOT NULL,
            event_time TEXT NOT NULL
        )
    """)
    db.executemany(
        "INSERT INTO events (event_id, participant, title, event_time) VALUES (?, ?, ?, ?)",
        [
            (1, "Rahul", "Phoenix architecture proposal review", "2026-07-17 15:00"),
            (2, "Rahul", "Weekly sync", "2026-07-14 10:00"),
            (3, "Priya", "Budget planning", "2026-07-16 09:00"),
            (4, "Rahul", "Document discussion re: Phoenix rollout", "2026-07-15 16:30"),
        ],
    )

    # --- Vector side: a virtual table storing embeddings for the same rows ---
    # rowid alignment lets us join structured filters to vector similarity directly.
    db.execute("""
        CREATE VIRTUAL TABLE event_embeddings USING vec0(
            event_id INTEGER PRIMARY KEY,
            embedding FLOAT[4]
        )
    """)

    # Fake 4-dim embeddings standing in for EmbeddingGemma output.
    # Row 4's title mentions "document discussion" -> make it closest to the query vector.
    fake_embeddings = {
        1: [0.10, 0.90, 0.05, 0.02],
        2: [0.90, 0.10, 0.05, 0.02],
        3: [0.05, 0.05, 0.90, 0.10],
        4: [0.02, 0.03, 0.05, 0.95],  # closest to query vector below
    }
    for eid, vec in fake_embeddings.items():
        db.execute(
            "INSERT INTO event_embeddings (event_id, embedding) VALUES (?, ?)",
            (eid, serialize_f32(vec)),
        )

    query_vec = serialize_f32([0.01, 0.02, 0.04, 0.97])  # "document discussion"

    print("\n--- Test 1: JOIN structured filter (participant='Rahul') with vector KNN in one statement ---")
    rows = db.execute(
        """
        SELECT e.event_id, e.title, e.event_time, ee.distance
        FROM event_embeddings ee
        JOIN events e ON e.event_id = ee.event_id
        WHERE ee.embedding MATCH ?
          AND e.participant = 'Rahul'
          AND k = 5
        ORDER BY ee.distance
        """,
        (query_vec,),
    ).fetchall()
    for r in rows:
        print(r)

    print("\n--- Test 2: same query, but structured filter excludes the true match (participant='Priya') ---")
    rows2 = db.execute(
        """
        SELECT e.event_id, e.title, e.event_time, ee.distance
        FROM event_embeddings ee
        JOIN events e ON e.event_id = ee.event_id
        WHERE ee.embedding MATCH ?
          AND e.participant = 'Priya'
          AND k = 5
        ORDER BY ee.distance
        """,
        (query_vec,),
    ).fetchall()
    for r in rows2:
        print(r)

    assert len(rows) > 0, "combined structured+vector query returned nothing"
    assert rows[0][0] == 4, f"expected event_id 4 (closest embedding) to rank first, got {rows[0][0]}"
    assert len(rows2) == 1 and rows2[0][0] == 3, "structured filter did not correctly restrict the KNN search"

    print("\nRESULT: sqlite-vec supports combined structured (WHERE) + vector similarity "
          "(MATCH ... ORDER BY distance) in a single SQL statement, joined against a "
          "regular relational table by rowid/event_id. Confirmed.")


if __name__ == "__main__":
    main()
