"""Create the download_tasks table."""
from alembic import op
import sqlalchemy as sa

revision = "0001_download_tasks"
down_revision = None
branch_labels = None
depends_on = None

def upgrade() -> None:
    op.create_table(
        "download_tasks",
        sa.Column("id", sa.Uuid(), nullable=False),
        sa.Column("platform_id", sa.String(length=80), nullable=False),
        sa.Column("source_url", sa.Text(), nullable=False),
        sa.Column("status", sa.String(length=24), server_default="queued", nullable=False),
        sa.Column("progress", sa.Integer(), server_default="0", nullable=False),
        sa.Column("error_code", sa.String(length=80), nullable=True),
        sa.Column("error_message", sa.String(length=500), nullable=True),
        sa.Column("metadata", sa.JSON(), server_default=sa.text("'{}'"), nullable=False),
        sa.Column("output_key", sa.String(length=1024), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_download_tasks_status_created_at", "download_tasks", ["status", "created_at"])
    op.create_index("ix_download_tasks_platform_id", "download_tasks", ["platform_id"])

def downgrade() -> None:
    op.drop_index("ix_download_tasks_platform_id", table_name="download_tasks")
    op.drop_index("ix_download_tasks_status_created_at", table_name="download_tasks")
    op.drop_table("download_tasks")
