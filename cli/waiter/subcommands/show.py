from tabulate import tabulate
from functools import reduce

from waiter import terminal
from waiter.data_format import display_data
from waiter.format import format_field_name, format_mem_field, format_timestamp_string

from waiter.display import tabulate_token_services
from waiter.querying import get_target_cluster_from_token, print_no_data, query_token
from waiter.util import guard_no_cluster


def tabulate_token(cluster_or_cluster_group_name, token, token_name, services, token_etag):
    """Given a token, returns a string containing tables for the fields"""
    table = [['Owner', token['owner']]]
    if token.get('name'):
        table.append(['Name', token['name']])
    if token.get('cluster'):
        table.append(['Primary Cluster', token['cluster']])
    if token.get('cpus'):
        table.append(['CPUs', token['cpus']])
    if token.get('mem'):
        table.append(['Memory', format_mem_field(token)])
    if token.get('ports'):
        table.append(['Ports requested', token['ports']])
    if token.get('cmd-type'):
        table.append(['Command type', token['cmd-type']])
    if token.get('health-check-url'):
        table.append(['Health check endpoint', token['health-check-url']])
    if token.get('permitted-user'):
        table.append(['Permitted user(s)', token['permitted-user']])

    explicit_keys = ('cluster', 'cmd', 'cmd-type', 'cpus', 'env', 'health-check-url', 'last-update-time',
                     'last-update-user', 'mem', 'name', 'owner', 'permitted-user', 'ports')
    ignored_keys = ('previous', 'root')
    for key, value in token.items():
        if key not in (explicit_keys + ignored_keys):
            table.append([format_field_name(key), value])

    command = token.get('cmd')
    if command:
        token_command = f'Command:\n{command}'
    else:
        token_command = '<No command specified>'

    if token.get('env') and len(token['env']) > 0:
        environment = '\n\nEnvironment:\n%s' % '\n'.join(['%s=%s' % (k, v) for k, v in token['env'].items()])
    else:
        environment = ''

    table_text = tabulate(table, tablefmt='plain')
    last_update_time = format_timestamp_string(token['last-update-time'])
    last_update_user = f' ({token["last-update-user"]})' if 'last-update-user' in token else ''
    column_names = ['Service Id', 'Cluster', 'Run as user', 'Instances', 'CPUs', 'Memory', 'Version', 'Status', 'Last request',
                    'Current?']
    service_table, _ = tabulate_token_services(services, token_name, token_etag=token_etag, column_names=column_names)
    return f'\n' \
        f'=== {terminal.bold(cluster_or_cluster_group_name)} / {terminal.bold(token_name)} ===\n' \
        f'\n' \
        f'Last Updated: {last_update_time}{last_update_user}\n' \
        f'\n' \
        f'{table_text}\n' \
        f'\n' \
        f'{token_command}' \
        f'{environment}' \
        f'{service_table}'


def show(clusters, args, _, enforce_cluster):
    """Prints info for the token with the given token name."""
    guard_no_cluster(clusters)
    as_json = args.get('json')
    as_yaml = args.get('yaml')
    token_name = args.get('token')[0]
    include_services = not args.get('no-services')

    query_result = query_token(clusters, token_name, include_services=include_services)
    if as_json or as_yaml:
        display_data(args, query_result)
    elif enforce_cluster:
        for cluster_name, entities in query_result['clusters'].items():
            services = [{'cluster': cluster_name, **service}
                        for service in entities.get('services', [])]
            print(tabulate_token(cluster_name, entities['token'], token_name, services, entities['etag']))
            print()
    else:
        clusters_in_result = [cluster
                              for cluster in clusters
                              if cluster['name'] in query_result['clusters']]
        def add_cluster_to_cluster_group_dict(cluster_groups, cluster):
            cluster_name = cluster['name']
            cluster_group_name = cluster.get('sync-group', cluster_name)
            clusters_in_group = [*cluster_groups.get(cluster_group_name, []), cluster]
            return {**cluster_groups, f'{cluster_group_name}': clusters_in_group}
        cluster_group_name_to_clusters = reduce(add_cluster_to_cluster_group_dict, clusters_in_result, dict())

        for cluster_group_name, cluster_group_clusters in cluster_group_name_to_clusters.items():
            primary_cluster = get_target_cluster_from_token(cluster_group_clusters, token_name, False)
            entities = query_result['clusters'][primary_cluster['name']]
            all_services_in_cluster_group = [{'cluster': cluster['name'], **service}
                                             for cluster in cluster_group_clusters
                                             for service in query_result['clusters'][cluster['name']].get('services', [])]
            print(tabulate_token(cluster_group_name, entities['token'], token_name, all_services_in_cluster_group, entities['etag']))
            print()

    if query_result['count'] > 0:
        return 0
    else:
        if not as_json and not as_yaml:
            print_no_data(clusters)
        return 1


def register(add_parser):
    """Adds this sub-command's parser and returns the action function"""
    show_parser = add_parser('show', help='show token by name')
    show_parser.add_argument('token', nargs=1)
    show_parser.add_argument('--no-services', help="don't show the token's services",
                             dest='no-services', action='store_true')
    format_group = show_parser.add_mutually_exclusive_group()
    format_group.add_argument('--json', help='show the data in JSON format', dest='json', action='store_true')
    format_group.add_argument('--yaml', help='show the data in YAML format', dest='yaml', action='store_true')
    return show
