"use client";
import Link from "next/link";
export function More() {
  return (
    <>
      <header>
        <h1>More</h1>
      </header>
      <section className="panel">
        <Link className="list-item" href="/history">
          History →
        </Link>
        <Link className="list-item" href="/settings">
          Settings →
        </Link>
      </section>
    </>
  );
}
