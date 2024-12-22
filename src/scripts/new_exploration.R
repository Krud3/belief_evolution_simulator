library(pacman)
pacman::p_load(
  RPostgres, data.table, ggplot2, ggthemes, scales, plotly, viridis, hrbrthemes,
  igraph, ggraph, dplyr, svglite, tidygraph, stringr
)

# Create a new DBI connection
conn <- dbConnect(RPostgres::Postgres(),
                  dbname = "promueva_new",
                  host = "localhost",
                  port = 5433,
                  user = "postgres",
                  password = "postgres")

network <- "
SELECT id FROM
	networks
WHERE
	run_id = (SELECT id FROM runs ORDER BY id DESC LIMIT 1)
ORDER BY final_round LIMIT 1;
"
q0_res <- as.data.table(dbGetQuery(conn, network))
current_network <- ""
current_network <- q0_res$id

speaking_agents <- str_glue("
SELECT * FROM agent_states_speaking WHERE agent_id in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

silent_agents <-  str_glue("
SELECT * FROM agent_states_silent WHERE agent_id in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

neighbors <- str_glue("
SELECT * FROM neighbors WHERE source in (
    SELECT id
    FROM agents
    WHERE network_id = '{current_network}'
);
")

agents <- str_glue("
SELECT * FROM agents WHERE network_id = '{current_network}';
")
# Base 01920c94-853a-7000-9764-060412ab4ccb
# extended to 3 01920dcc-ab76-7000-921f-cbc78d33b8f5
# extended to 3 confidence 01920e15-2cc5-7000-a5a5-3e82c904f172
# Perpetual silence

# 01920db8-940e-7000-a5b8-7fa486907dbe

speaking_agents <- as.data.table(dbGetQuery(conn, speaking_agents))
silent_agents <- as.data.table(dbGetQuery(conn, silent_agents))
neighbors <- as.data.table(dbGetQuery(conn, neighbors))
agents <- as.data.table(dbGetQuery(conn, agents))
dbDisconnect(conn)

speaking_agents[, is_speaking := TRUE]
silent_agents[, is_speaking := FALSE]

agent_rounds <- rbind(speaking_agents, silent_agents)

data_table_result <- merge(agent_rounds, agents[, .(id, tolerance_radius, name)], by.x = "agent_id", by.y = "id")
setnames(data_table_result, "name", "agent_label")

# Create a dataset with consecutive points for segments
data_segments <- data_table_result[order(agent_label, round)]
data_segments[, next_round := shift(round, type="lead"), by=agent_label]
data_segments[, next_belief := shift(belief, type="lead"), by=agent_label]

ggplot() +
  geom_segment(data = data_segments[!is.na(next_round)],
               aes(x = round, y = belief,
                   xend = next_round, yend = next_belief,
                   color = agent_label,
                   linetype = is_speaking)) +
  geom_point(data = data_table_result,
             aes(x = round, y = belief, color = agent_label), size = 2) +
  scale_y_continuous(limits = c(0, 1),
                     breaks = seq(0, 1, by = 0.1),    # Add breaks every 0.1
                     labels = sprintf("%.1f", seq(0, 1, by = 0.1))) +  # Format labels with one decimal
  scale_linetype_manual(values = c("FALSE" = "dashed", "TRUE" = "solid")) +
  labs(title = "Belief Evolution Over Time",
       x = "Round",
       y = "Belief",
       color = "Agent",
       linetype = "Is Speaking") +
  theme_minimal()
